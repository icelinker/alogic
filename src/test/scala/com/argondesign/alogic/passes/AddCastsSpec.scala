////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2018 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Geza Lore
//
// DESCRIPTION:
//
// Namer tests
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.passes

import com.argondesign.alogic.AlogicTest
import com.argondesign.alogic.SourceTextConverters._
import com.argondesign.alogic.ast.Trees.Expr.ImplicitConversions._
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.Warning
import com.argondesign.alogic.core.Types._
import com.argondesign.alogic.typer.Typer
import org.scalatest.FreeSpec

final class AddCastsSpec extends FreeSpec with AlogicTest {

  implicit val cc = new CompilerContext
  cc.postSpecialization = true

  val namer = new Namer
  val typer = new Typer
  val addCasts = new AddCasts

  def xform(tree: Tree) = {
    tree match {
      case Root(_, entity: Entity) => cc.addGlobalEntity(entity)
      case entity: Entity          => cc.addGlobalEntity(entity)
      case _                       =>
    }
    val node = tree rewrite namer match {
      case Root(_, entity) => entity
      case other           => other
    }
    node rewrite typer rewrite addCasts
  }

  "AddCasts should automatically insert casts" - {
    "to infer sizes of unsized literals in" - {
      "binary operator operands" - {
        for (op <- List("*", "/", "%", "+", "-", "&", "|", "^", ">", ">=", "<", "<=", "==", "!=")) {
          for {
            (text, res) <- List(
              (s"8'd3 ${op} 2", Some(Right(ExprCast(TypeUInt(8), 2)))),
              (s"2 ${op} 8'd3", Some(Left(ExprCast(TypeUInt(8), 2)))),
              (s"8'd3 ${op} -2s", Some(Right(ExprCast(TypeSInt(8), ExprNum(true, -2))))),
              (s"-2s ${op} 8'd3", Some(Left(ExprCast(TypeSInt(8), ExprNum(true, -2))))),
              (s"7'sd3 ${op} 2", Some(Right(ExprCast(TypeUInt(7), 2)))),
              (s"2 ${op} 7'sd3", Some(Left(ExprCast(TypeUInt(7), 2)))),
              (s"7'sd3 ${op} -2s", Some(Right(ExprCast(TypeSInt(7), ExprNum(true, -2))))),
              (s"-2s ${op} 7'sd3", Some(Left(ExprCast(TypeSInt(7), ExprNum(true, -2))))),
              (s"4 ${op} 2 ", None)
            )
          } {
            text in {
              val expr = text.asTree[Expr]
              xform(expr) match {
                case result @ ExprBinary(lhs, _, rhs) =>
                  res match {
                    case Some(Left(l))  => lhs shouldBe l
                    case Some(Right(r)) => rhs shouldBe r
                    case None           => result shouldBe expr
                  }
                  cc.messages filterNot { _.isInstanceOf[Warning] } shouldBe empty
                case _ => fail()
              }
            }
          }
        }
      }

      "ternary operator operands" - {
        for {
          (text, res) <- List(
            ("0 ? 0 : 2'd1", Some(Left(ExprCast(TypeUInt(2), 0)))),
            ("0 ? 2'd1 : 0", Some(Right(ExprCast(TypeUInt(2), 0)))),
            ("0 ? 0 : 3'sd1", Some(Left(ExprCast(TypeUInt(3), 0)))),
            ("0 ? 3'sd1 : 0", Some(Right(ExprCast(TypeUInt(3), 0)))),
            ("0 ? 0s : 2'd1", Some(Left(ExprCast(TypeSInt(2), ExprNum(true, 0))))),
            ("0 ? 2'd1 : 0s", Some(Right(ExprCast(TypeSInt(2), ExprNum(true, 0))))),
            ("0 ? 0s : 3'sd1", Some(Left(ExprCast(TypeSInt(3), ExprNum(true, 0))))),
            ("0 ? 3'sd1 : 0s", Some(Right(ExprCast(TypeSInt(3), ExprNum(true, 0))))),
            ("0 ? 1 : 0", None)
          )
        } {
          text in {
            val expr = text.asTree[Expr]
            xform(expr) match {
              case result @ ExprTernary(_, lhs, rhs) =>
                res match {
                  case Some(Left(l))  => lhs shouldBe l
                  case Some(Right(r)) => rhs shouldBe r
                  case None           => result shouldBe expr
                }
                cc.messages shouldBe empty
              case _ => fail()
            }
          }
        }
      }

      "index expressions" - {
        def check(expr: Expr, expected: List[Expr]): Unit = expr match {
          case ExprIndex(e, i) =>
            expected match {
              case v :: vs =>
                i shouldBe v
                if (vs.nonEmpty) check(e, vs)
              case _ => fail()
            }
          case _ => fail()
        }

        for {
          (index, res) <- List(
            // format: off
            ("u8 a; (* unused *) u1 b = a[0]", List(ExprCast(TypeUInt(3), 0))),
            ("u9 a; (* unused *) u1 b = a[0]", List(ExprCast(TypeUInt(4), 0))),
            ("u32[8] a; (* unused *) u32 b = a[0]", List(ExprCast(TypeUInt(3), 0))),
            ("u33[9] a; (* unused *) u33 b = a[0]", List(ExprCast(TypeUInt(4), 0))),
            ("u32[8] a; (* unused *) u1 b = a[0][2]", List(ExprCast(TypeUInt(5), 2), ExprCast(TypeUInt(3), 0))),
            ("u33[9] a; (* unused *) u1 b = a[0][2]", List(ExprCast(TypeUInt(6), 2), ExprCast(TypeUInt(4), 0)))
            // format: on
          )
        } {
          index in {
            val entity = s"""|fsm f {
                             |  void main() {
                             |    ${index};
                             |    fence;
                             |  }
                             |}""".stripMargin.asTree[Entity]
            val tree = xform(entity)
            val expr = tree getFirst { case Decl(_, Some(i)) => i }
            check(expr, res)
            cc.messages shouldBe empty
          }
        }
      }

      "slice expressions" - {
        def check(expr: Expr, expected: List[(Expr, Expr)]): Unit = expr match {
          case ExprSlice(e, l, _, r) =>
            expected match {
              case (vl, vr) :: vs =>
                l shouldBe vl
                r shouldBe vr
                if (vs.nonEmpty) check(e, vs)
              case _ => fail()
            }
          case _ => fail()
        }

        for {
          (slice, res) <- List(
            // format: off
            ("u8 a; (* unused *) u2 b = a[1:0]", List((ExprCast(TypeUInt(3), 1), ExprCast(TypeUInt(3), 0)))),
            ("u9 a; (* unused *) u2 b = a[1:0]", List((ExprCast(TypeUInt(4), 1), ExprCast(TypeUInt(4), 0)))),
            ("u8 a; (* unused *) u1 b = a[2+:1]", List((ExprCast(TypeUInt(3), 2), ExprCast(TypeUInt(4), 1)))),
            ("u9 a; (* unused *) u1 b = a[2+:1]", List((ExprCast(TypeUInt(4), 2), ExprCast(TypeUInt(4), 1)))),
            ("u8 a; (* unused *) u1 b = a[2-:1]", List((ExprCast(TypeUInt(3), 2), ExprCast(TypeUInt(4), 1)))),
            ("u9 a; (* unused *) u1 b = a[2-:1]", List((ExprCast(TypeUInt(4), 2), ExprCast(TypeUInt(4), 1)))),
            ("u32[8] a; (* unused *) u32[2] b = a[1:0]", List((ExprCast(TypeUInt(3), 1), ExprCast(TypeUInt(3), 0)))),
            ("u33[9] a; (* unused *) u33[2] b = a[1:0]", List((ExprCast(TypeUInt(4), 1), ExprCast(TypeUInt(4), 0)))),
            ("u32[8] a; (* unused *) u32[1] b = a[2+:1]", List((ExprCast(TypeUInt(3), 2), ExprCast(TypeUInt(4), 1)))),
            ("u33[9] a; (* unused *) u33[1] b = a[2+:1]", List((ExprCast(TypeUInt(4), 2), ExprCast(TypeUInt(4), 1)))),
            ("u32[8] a; (* unused *) u32[1] b = a[2-:1]", List((ExprCast(TypeUInt(3), 2), ExprCast(TypeUInt(4), 1)))),
            ("u33[9] a; (* unused *) u33[1] b = a[2-:1]", List((ExprCast(TypeUInt(4), 2), ExprCast(TypeUInt(4), 1)))),
            // format: on
          )
        } {
          slice in {
            val entity = s"""|fsm f {
                             |  void main() {
                             |    ${slice};
                             |    fence;
                             |  }
                             |}""".stripMargin.asTree[Entity]
            val tree = xform(entity)
            val expr = tree getFirst { case Decl(_, Some(i)) => i }
            check(expr, res)
            cc.messages shouldBe empty
          }
        }
      }

      "initializer expressions" - {
        for {
          (decl, pattern) <- List[(String, PartialFunction[Any, Unit])](
            // format: off
            ("(* unused *) i8 a = 2s", { case ExprCast(TypeSInt(Expr(8)), ExprNum(true, v)) if v == 2 => }),
            ("(* unused *) u8 a = 2s", { case ExprCast(TypeSInt(Expr(8)), ExprNum(true, v)) if v == 2 => }),
            ("(* unused *) i7 a = 2", { case ExprCast(TypeUInt(Expr(7)), ExprNum(false, v)) if v == 2 => }),
            ("(* unused *) u7 a = 2", { case ExprCast(TypeUInt(Expr(7)), ExprNum(false, v)) if v == 2 => }),
            ("(* unused *) int  a = 2s", { case ExprNum(true, v) if v == 2 => }),
            ("(* unused *) uint a = 2s", { case ExprCall(ExprSym(s), List(ExprNum(true, v))) if v == 2 && s.name == "$unsigned" => }),
            ("(* unused *) int  a = 2", { case ExprCall(ExprSym(s), List(ExprNum(false, v))) if v == 2 && s.name == "$signed" => }),
            ("(* unused *) uint a = 2", { case ExprNum(false, v) if v == 2 => })
            // format: on
          )
        } {
          decl in {
            val entity = s"""|fsm f {
                             |  void main() {
                             |    ${decl}; 
                             |    fence;
                             |  }
                             |}""".stripMargin.asTree[Entity]
            val tree = xform(entity)
            val init = tree getFirst { case Decl(_, Some(i)) => i }
            cc.messages foreach println
            init should matchPattern(pattern)
            cc.messages shouldBe empty
          }
        }
      }

      "right hand sides of assignments" - {
        for {
          (assign, res) <- List(
            ("i8 a = 0; a = 2s", ExprCast(TypeSInt(8), ExprNum(true, 2))),
            ("u8 a = 0; a = 2s", ExprCast(TypeSInt(8), ExprNum(true, 2))),
            ("i7 a = 0; a = 2", ExprCast(TypeUInt(7), ExprNum(false, 2))),
            ("u7 a = 0; a = 2", ExprCast(TypeUInt(7), ExprNum(false, 2))),
            ("i8 a = 0; a += 2s", ExprCast(TypeSInt(8), ExprNum(true, 2))),
            ("u8 a = 0; a += 2s", ExprCast(TypeSInt(8), ExprNum(true, 2))),
            ("i7 a = 0; a += 2", ExprCast(TypeUInt(7), ExprNum(false, 2))),
            ("u7 a = 0; a += 2", ExprCast(TypeUInt(7), ExprNum(false, 2)))
          )
        } {
          assign in {
            val entity = s"""|fsm f {
                             |  void main() {
                             |    ${assign};
                             |    fence;
                             |  }
                             |}""".stripMargin.asTree[Entity]
            val tree = xform(entity)
            val rhs = tree getFirst {
              case StmtAssign(_, rhs)    => rhs
              case StmtUpdate(_, _, rhs) => rhs
            }
            rhs shouldBe res
            cc.messages shouldBe empty
          }
        }
      }

      "function argumentss expressions" - {
        for {
          (call, res) <- List(
            // format: off
            ("a.write(0s)", ExprCast(TypeSInt(1), ExprNum(true, 0))),
            ("a.write(1u)", ExprCast(TypeUInt(1), ExprNum(false, 1))),
            ("b.write(2s)", ExprCast(TypeSInt(10), ExprNum(true, 2))),
            ("b.write(3u)", ExprCast(TypeUInt(10), ExprNum(false, 3))),
            ("c.write(4s)", ExprCast(TypeSInt(20), ExprNum(true, 4))),
            ("c.write(5u)", ExprCast(TypeUInt(20), ExprNum(false, 5)))
            // format: on
          )
        } {
          call in {
            val entity = s"""|fsm f {
                             |  (* unused *) out sync bool a;
                             |  (* unused *) out sync u10 b;
                             |  (* unused *) out sync i20 c;
                             |  void main() {
                             |    ${call};
                             |    fence;
                             |  }
                             |}""".stripMargin.asTree[Entity]
            val tree = xform(entity)
            val ExprCall(_, List(expr)) = tree getFirst { case StmtExpr(expr) => expr }
            expr shouldBe res
            cc.messages shouldBe empty
          }
        }
      }
    }
  }
}
