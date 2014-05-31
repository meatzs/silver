package semper.sil.ast.utility

import scala.language.postfixOps
import semper.sil.ast._

/** Utility methods for types. */
object Types {

  /** Returns all type variables that occur inside the given type, including the type itself if
    * it is a type variable.
    *
    * @param typ A type to extract type variables from.
    * @return The set of type variables that occur inside {}
    */
  def typeVariables(typ: Type): Set[TypeVar] = typ match {
    case t: TypeVar => Set(t)
    case dt@DomainType(domain, typeVarsMap) => (dt.domainTypVars filterNot typeVarsMap.contains).toSet
    case _ => Set()
  }

  /** Lifts [[semper.sil.ast.utility.Types.typeVariables]] to a set of types. */
  def typeVariables(types: Set[Type]): Set[TypeVar] = types flatMap typeVariables

  /** Returns all free type variables used in the signature of the given domain function.
    *
    * @param domainFunction A domain functions whose free type variables are to be collected.
    * @return The set of free type variables used by the given domain function.
    */
  def freeTypeVariables(domainFunction: DomainFunc): Set[TypeVar] = (
       typeVariables(domainFunction.formalArgs map (_.typ) toSet)
    ++ typeVariables(domainFunction.typ))

  /** Returns all free types variables used in the signature of the given domain axiom.
    *
    * @param domainAxiom A domain axiom whose free type variables are to be collected.
    * @return The set of free type variables used by the given domain axiom.
    */
  def freeTypeVariables(domainAxiom: DomainAxiom): Set[TypeVar] = {
    var tvs = Set[TypeVar]()

    domainAxiom.exp visit {case t: Typed => tvs ++= typeVariables(t.typ)}

    tvs
  }

  /** Lifts [[semper.sil.ast.utility.Types..freeTypeVariables]] to general domain members. */
  def freeTypeVariables(domainMember: DomainMember): Set[TypeVar] = domainMember match {
    case df: DomainFunc => freeTypeVariables(df)
    case da: DomainAxiom => freeTypeVariables(da)
  }

  /** Returns a list of transitive "subtypes" of the given `typ`. For example, type
    * constituents of `Seq[Seq[Int]]` are `Seq[Int]` and `Int`.
    *
    * @param typ A type whose type constituents are to be returned.
    * @return The list of transitive type constituents of `typ`.
    */
  def typeConstituents(typ: Type): List[Type] = typ match {
    case Int | Bool | Perm | Ref | Pred | _: TypeVar => Nil
    case dt: DomainType => dt.domainTypVars.map(_.substitute(dt.typVarsMap)).toList
    case SeqType(elementType) => elementType :: typeConstituents(elementType)
    case SetType(elementType) => elementType :: typeConstituents(elementType)
    case MultisetType(elementType) => elementType :: typeConstituents(elementType)
  }
}
