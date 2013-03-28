package semper.sil.parser

import org.kiama.util.Messaging.message
import org.kiama.util.Messaging.messagecount
import org.kiama.util.Positioned

/**
 * A resolver and type-checker for the intermediate SIL AST.
 */
case class Resolver(p: PProgram) {
  val names = NameAnalyser()
  val typechecker = TypeChecker(names)

  def run: Boolean = {
    if (names.run(p)) {
      if (typechecker.run(p)) {
        return true
      }
    }
    false
  }
}

/**
 * Performs type-checking and sets the type of all typed nodes.
 */
case class TypeChecker(names: NameAnalyser) {

  import TypeHelper._

  var curMethod: PMethod = null

  def run(p: PProgram): Boolean = {
    check(p)
    messagecount == 0
  }

  def check(p: PProgram) {
    p.methods map check
    // TODO: check fields/functions/...
  }

  def check(m: PMethod) {
    curMethod = m
    m.pres map (check(_, Bool))
    m.posts map (check(_, Bool))
    check(m.body)
    curMethod = null
  }

  def check(stmt: PStmt) {
    stmt match {
      case PSeqn(ss) =>
        ss map check
      case PFold(e) =>
        check(e, Bool)
      case PUnfold(e) =>
        check(e, Bool)
      case PExhale(e) =>
        check(e, Bool)
      case PInhale(e) =>
        check(e, Bool)
      case PVarAssign(idnuse, rhs) =>
        names.definition(curMethod)(idnuse) match {
          case PLocalVarDecl(_, typ, _) =>
            check(idnuse, typ)
            check(rhs, typ)
          case PFormalArgDecl(_, typ) =>
            check(idnuse, typ)
            check(rhs, typ)
          case _ =>
            message(stmt, "expected variable as lhs")
        }
      case PNewStmt(idnuse) =>
        names.definition(curMethod)(idnuse) match {
          case PLocalVarDecl(_, typ, _) =>
            check(idnuse, Ref)
          case PFormalArgDecl(_, typ) =>
            check(idnuse, Ref)
          case _ =>
            message(stmt, "expected variable as lhs")
        }
      case PFieldAssign(field, rhs) =>
        names.definition(curMethod)(field.idnuse) match {
          case PField(_, typ) =>
            check(field, typ)
            check(rhs, typ)
          case _ =>
            message(stmt, "expected a field as lhs")
        }
      case PIf(cond, thn, els) =>
        check(cond, Bool)
        check(thn)
        check(els)
      case PWhile(cond, invs, body) =>
        check(cond, Bool)
        invs map (check(_, Bool))
        check(body)
      case PLocalVarDecl(idndef, typ, init) =>
        init match {
          case Some(i) => check(i, typ)
          case None =>
        }
      case PFreshReadPerm(vars, s) =>
        vars map {
          v =>
            names.definition(curMethod)(v) match {
              case PLocalVarDecl(_, typ, _) =>
                check(v, Perm)
              case PFormalArgDecl(_, typ) =>
                check(v, Perm)
              case _ =>
                message(v, "expected variable in fresh read permission block")
            }
        }
    }
  }

  /**
   * Type-check and resolve e and ensure that it has type expected.  If that is not the case, then an
   * error should be issued.
   */
  def check(exp: PExp, expected: PType) {
    def setType(actual: PType) {
      if (actual == PUnkown()) {
        return // no error for unknown type (an error has already been issued)
      }
      if (expected == actual) {
        exp.typ = expected
      } else {
        message(exp, s"expected $expected, but got $actual")
      }
    }
    exp match {
      case i@PIdnUse(name) =>
        names.definition(curMethod)(i) match {
          case PLocalVarDecl(_, typ, _) =>
            setType(typ)
          case PFormalArgDecl(_, typ) =>
            setType(typ)
          case PField(_, typ) =>
            setType(typ)
          case x =>
            message(i, s"expected variable or field, but got $x")
        }
      case PBinExp(left, op, right) =>
        op match {
          case "+" | "-" | "*" =>
            check(left, expected)
            check(right, expected)
            setType(expected)
          case "/" => ???
          case "%" => ???
          case "<" | "<=" | ">" | ">=" =>
            val l = possibleTypes(left)
            val r = possibleTypes(right)
            val t = l intersect r
            if (t.size > 0) {
              check(left, t(0))
              check(right, t(0))
            } else {
              message(exp, s"expected two Ints or two Perms, but found $l and $r")
            }
            setType(Bool)
          case "==" | "!=" =>
            val l = possibleTypes(left)
            val r = possibleTypes(right)
            (l intersect r) match {
              case Nil =>
                message(exp, s"require same type for left/right side, but found $l and $r")
              case t :: ts =>
                check(left, t)
                check(right, t)
            }
            setType(Bool)
          case "&&" | "||" | "<==>" | "==>" =>
            check(left, Bool)
            check(right, Bool)
            setType(Bool)
          case _ => sys.error(s"unexpected operator $op")
        }
      case PUnExp(op, e) =>
        op match {
          case "-" =>
            check(e, expected)
            setType(expected)
          case _ => sys.error(s"unexpected operator $op")
        }
      case PIntLit(i) =>
        setType(Int)
      case PResultLit() => ???
      case PBoolLit(b) =>
        setType(Bool)
      case PNullLit() =>
        setType(Ref)
      case PFieldAcc(rcv, idnuse) =>
        check(rcv, Ref)
        check(idnuse, expected)
    }
  }

  /**
   * Returns the set of types that exp might possibly have.
   */
  def possibleTypes(exp: PExp): Seq[PType] = {
    exp match {
      case i@PIdnUse(name) =>
        names.definition(curMethod)(i) match {
          case PLocalVarDecl(_, typ, _) =>
            Seq(typ)
          case PFormalArgDecl(_, typ) =>
            Seq(typ)
          case PField(_, typ) =>
            Seq(typ)
          case _ => Nil
        }
      case PBinExp(left, op, right) =>
        val l = possibleTypes(left)
        val r = possibleTypes(right)
        op match {
          case "+" | "-" | "*" =>
            (Seq(Int, Perm) intersect l) intersect r
          case "/" => ???
          case "%" => ???
          case "<" | "<=" | ">" | ">=" =>
            (Seq(Int, Perm) intersect l) intersect r
          case "==" | "!=" =>
            Seq(Bool)
          case "&&" | "||" | "<==>" | "==>" =>
            Seq(Bool)
          case _ => sys.error(s"unexpected operator $op")
        }
      case PUnExp(op, e) =>
        op match {
          case "-" => possibleTypes(e)
          case _ => sys.error(s"unexpected operator $op")
        }
      case PIntLit(i) => Seq(Int)
      case PResultLit() => ???
      case PBoolLit(b) => Seq(Bool)
      case PNullLit() => Seq(Ref)
      case PFieldAcc(rcv, idnuse) =>
        names.definition(curMethod)(idnuse) match {
          case PField(_, typ) =>
            Seq(typ)
          case _ => Nil
        }
    }
  }

  /**
   * If b is false, report an error for node.
   */
  def ensure(b: Boolean, node: Positioned, msg: String) {
    if (!b) message(node, msg)
  }
}

/**
 * Resolves identifiers to their declaration.
 */
case class NameAnalyser() {

  def definition(method: PMethod)(idnuse: PIdnUse) = {
    if (method == null) {
      idnMap.get(idnuse.name).get.asInstanceOf[RealEntity]
    }
    else {
      // lookup in method map first, and otherwise in the general one
      methodIdnMap.get(method).get.getOrElse(idnuse.name,
        idnMap.get(idnuse.name).get
      ).asInstanceOf[RealEntity]
    }
  }

  private val idnMap = collection.mutable.HashMap[String, Entity]()
  private val methodIdnMap = collection.mutable.HashMap[PMethod, collection.mutable.HashMap[String, Entity]]()

  def run(p: PProgram): Boolean = {
    var curMap = idnMap
    var curMethod: PMethod = null
    def getMap = if (curMethod == null) idnMap else methodIdnMap.get(curMethod).get
    // find all declarations
    p.visit({
      case m: PMethod =>
        methodIdnMap.put(m, collection.mutable.HashMap[String, Entity]())
        curMethod = m
      case i@PIdnDef(name) =>
        getMap.get(name) match {
          case Some(MultipleEntity()) =>
            message(i, s"$name already defined.")
          case Some(e) =>
            message(i, s"$name already defined.")
            getMap.put(name, MultipleEntity())
          case None =>
            val e = i.parent match {
              case decl: PProgram => decl
              case decl: PMethod => decl
              case decl: PLocalVarDecl => decl
              case decl: PFormalArgDecl => decl
              case decl: PField => decl
              case decl: PFunction => decl
              case decl: PPredicate => decl
              case decl: PDomain => decl
              case _ => sys.error(s"unexpected parent of identifier: ${i.parent}")
            }
            getMap.put(name, e)
        }
      case _ =>
    }, {
      case m: PMethod =>
        curMethod = null
      case _ =>
    })
    // check all identifier uses
    p.visit({
      case m: PMethod =>
        curMethod = m
      case i@PIdnUse(name) =>
        // look up in both maps (if we are not in a method currently, we look in the same map twice, but that is ok)
        getMap.getOrElse(name, idnMap.getOrElse(name, UnknownEntity())) match {
          case UnknownEntity() =>
            message(i, s"$name not defined.")
          case _ =>
        }
      case _ =>
    }, {
      case m: PMethod =>
        curMethod = null
      case _ =>
    })
    messagecount == 0
  }
}
