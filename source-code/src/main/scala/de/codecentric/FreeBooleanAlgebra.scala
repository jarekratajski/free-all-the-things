package de.codecentric

//snippet:boolean algebra
trait BoolAlgebra[A] {
  def tru: A
  def fls: A

  def not(value: A): A

  def and(lhs: A, rhs: A): A
  def or(lhs: A, rhs: A): A
}
//end

object BoolAlgebra {
  def apply[A](implicit B: BoolAlgebra[A]): BoolAlgebra[A] = B

  implicit val bool: BoolAlgebra[Boolean] = new BoolAlgebra[Boolean] {
    def tru: Boolean = true
    def fls: Boolean = false

    def not(value: Boolean): Boolean = !value

    def and(lhs: Boolean, rhs: Boolean): Boolean = lhs && rhs
    def or(lhs: Boolean, rhs: Boolean): Boolean = lhs || rhs
  }
}

object FreeBool {
  //snippet:free bool
  sealed abstract class FreeBool[+A]

  case object Tru extends FreeBool[Nothing]
  case object Fls extends FreeBool[Nothing]

  case class Not[A](value: FreeBool[A]) extends FreeBool[A]
  case class And[A](lhs: FreeBool[A], rhs: FreeBool[A]) extends FreeBool[A]
  case class Or[A](lhs: FreeBool[A], rhs: FreeBool[A]) extends FreeBool[A]
  case class Inject[A](value: A) extends FreeBool[A]
  //end

  //snippet:free bool interp
  def runFreeBool[A,B](fb: FreeBool[A])(f: A => B)(implicit B: BoolAlgebra[B]): B = {
    fb match {
      case Tru => B.tru
      case Fls => B.fls
      case Inject(v) => f(v)
      case Not(v) => B.not(runFreeBool(v)(f))
      case Or(lhs, rhs) => B.or(runFreeBool(lhs)(f), runFreeBool(rhs)(f))
      case And(lhs, rhs) => B.and(runFreeBool(lhs)(f), runFreeBool(rhs)(f))
    }
  }
  //end

  def inject[A](v: A): FreeBool[A] = FreeBool.Inject(v)
  def and[A](lhs: FreeBool[A], rhs: FreeBool[A]): FreeBool[A] = And(lhs, rhs)
  def or[A](lhs: FreeBool[A], rhs: FreeBool[A]): FreeBool[A] = Or(lhs, rhs)
  def not[A](fb: FreeBool[A]): FreeBool[A] = Not(fb)
  val tru: FreeBool[Nothing] = Tru
  val fls: FreeBool[Nothing] = Fls
}

object FreeBoolSugar {
  import FreeBool._
  implicit def toFreeBoolOps[A](v: FreeBool[A]) = new FreeBoolOps(v)

  sealed class FreeBoolOps[A](self: FreeBool[A]) {
    def &(other: FreeBool[A]): FreeBool[A] = And(self, other)
    def |(other: FreeBool[A]): FreeBool[A] = Or(self, other)
    def unary_! : FreeBool[A] = Not(self)
  }

}

object FreeBoolDsl {
  import FreeBool._
  import FreeBoolSugar._

  type Date = String

  case class Site(terms: List[String], url: String, indexedAt: Date, text: String)

  object Sites {
    val flatMap = Site(List("Scala", "conference", "Oslo", "flatMap"), "2018.flatMap.no", "20180502", "...")
    val spring = Site(List("Java", "spring", "boot", "cloud"), "spring.io", "20180502", "spring")

    def all() = List(flatMap, spring)
  }

  //snippet:search predicate
  sealed trait Search
  case class Term(t: String) extends Search
  case class After(date: Date) extends Search
  case class InText(t: String) extends Search
  case class InUrl(url: String) extends Search

  // and the usual smart ctors
  //end

  def term(t: String): FreeBool[Search] = Inject(Term(t))
  def after(date: Date): FreeBool[Search] = Inject(After(date))
  def inText(t: String): FreeBool[Search] = Inject(InText(t))
  def inUrl(url: String): FreeBool[Search] = Inject(InUrl(url))

  //snippet:example search predicate
  val search = term("Scala") &
               after("20180101") &
               !(term("Java") | inText("spring")) &
               inUrl("flatmap")
  //end

  //snippet:eval search predicate
  def evalSearch(pred: FreeBool[Search])(site: Site): Boolean = {
    def nat(s: Search): Boolean = s match {
      case Term(t) => site.terms.contains(t)
      case After(d) => site.indexedAt > d
      case InText(t: String) => site.text.contains(t)
      case InUrl(w) => site.url.contains(w)
    }

    runFreeBool(pred)(nat)
  }

  val result = Sites.all().filter(evalSearch(search))
  //end
}

object FreeBoolPartial extends App {
  import FreeBoolDsl._
  import FreeBool._
  import FreeBoolSugar._

  sealed trait Predicate
  case class OlderThan(i: Int) extends Predicate
  case class MinSalary(i: Int) extends Predicate
  case class HasSkill(s: String) extends Predicate

  def olderThan(i: Int): FreeBool[Predicate] = Inject(OlderThan(i))
  def minSalary(i: Int): FreeBool[Predicate] = Inject(MinSalary(i))
  def hasSkill(s: String): FreeBool[Predicate] = Inject(HasSkill(s))

  def runFreeBoolP[A,B](f: A => Option[B], fb: FreeBool[A])(implicit B: BoolAlgebra[B]): Either[FreeBool[A], B] = {
    fb match {
      case Tru => Right(B.tru)
      case Fls => Right(B.fls)
      case Inject(v) => f(v).toRight(fb)
      case Not(v) => runFreeBoolP(f, v).map(B.not(_))
      case Or(lhs, rhs) =>
        val l = runFreeBoolP(f, lhs)
        val r = runFreeBoolP(f, rhs)

        (l, r) match {
          case (Right(a), Right(b)) => Right(B.or(a,b))
          case (Right(a), _) if a == B.tru=> Right(B.tru)
          case (Right(_), b) => b
          case (_, Right(b)) if b == B.tru => Right(B.tru)
          case (a, Right(_)) => a
          case _ => Left(fb)
        }
      case And(lhs, rhs) =>
        val l = runFreeBoolP(f, lhs)
        val r = runFreeBoolP(f, rhs)

        (l, r) match {
          case (Right(a), Right(b)) => Right(B.and(a,b))
          case (Right(a), _) if a == B.fls=> Right(B.fls)
          case (Right(_), b) => b
          case (_, Right(b)) if b == B.fls => Right(B.fls)
          case (a, Right(_)) => a
          case _ => Left(fb)
        }
    }
  }

  val p: FreeBool[Predicate] = !olderThan(80) & minSalary(100) & hasSkill("Scala") & hasSkill("DevOps")

  def partially(age: Int, skills: List[String])(p: Predicate): Option[Boolean] = p match {
    case OlderThan(i) => Some(age > i)
    case HasSkill(s) => Some(skills.contains(s))
    case _ => None
  }

  println(runFreeBoolP(partially(28, List("Scala", "Haskell", "FP")), p))
  println(runFreeBoolP(partially(28, List("Scala", "Haskell", "DevOps")), p))
}
