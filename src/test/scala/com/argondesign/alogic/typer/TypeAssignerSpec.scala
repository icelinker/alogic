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

package com.argondesign.alogic.typer

import com.argondesign.alogic.AlogicTest
import com.argondesign.alogic.SourceTextConverters._
import com.argondesign.alogic.ast.Trees.Expr.ImplicitConversions.int2ExprNum
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.FlowControlTypes._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.Loc
import com.argondesign.alogic.core.StorageTypes._
import com.argondesign.alogic.core.Symbols.ErrorSymbol
import com.argondesign.alogic.core.Types._
import com.argondesign.alogic.passes.Namer
import org.scalatest.FreeSpec

final class TypeAssignerSpec extends FreeSpec with AlogicTest {

  implicit val cc: CompilerContext = new CompilerContext
  lazy val namer = new Namer

  private def xform(tree: Tree) = {
    tree match {
      case Root(_, entity: Entity) => cc.addGlobalEntity(entity)
      case entity: Entity          => cc.addGlobalEntity(entity)
      case _                       =>
    }
    tree rewrite namer
  }

  // Apply type assigner to all children
  private def assignChildren(tree: Tree): Unit = tree.children foreach { child =>
    child.postOrderIterator foreach {
      case node: Tree => TypeAssigner(node)
      case _          => ()
    }
  }

  "The TypeAssigner should assign correct types to" - {
    "expressions" - {
      "names" - {
        "terms" - {
          for {
            (name, decl, kind) <- List(
              // format: off
              ("bool", "bool a;", TypeUInt(1)),
              ("u8", "u8 a;", TypeUInt(8)),
              ("i1", "i1 a;", TypeSInt(1)),
              ("i8", "i8 a;", TypeSInt(8)),
              ("struct", "s a;", TypeStruct("s", List("b", "c"), List(TypeUInt(1), TypeSInt(8)))),
              ("typedef", "t a;", TypeUInt(4)),
              ("u8[2]", "u8[2] a;", TypeVector(TypeUInt(8), 2)),
              ("memory u8[2]", "u8 a[2];", TypeArray(TypeUInt(8), 2)),
              ("param u8", " param u8 a = 8'd2;", TypeUInt(8)),
              ("const u8", " const u8 a = 8'd2;", TypeUInt(8)),
              ("pipeline u8", "pipeline u8 a;", TypeUInt(8)),
              ("in u8", "in u8 a;", TypeIn(TypeUInt(8), FlowControlTypeNone)),
              ("out u8", "out u8 a;", TypeOut(TypeUInt(8), FlowControlTypeNone, StorageTypeDefault)),
              ("function", "void a() {}", TypeCtrlFunc(Nil, TypeVoid))
              // format: on
            )
          } {
            name in {
              val root = s"""|typedef u4 t;
                             |
                             |struct s {
                             |  bool b;
                             |  i8 c;
                             |}
                             |
                             |fsm thing {
                             |  ${decl}
                             |  void main() {
                             |    a;
                             |  }
                             |}""".stripMargin.asTree[Root]
              val tree = xform(root)

              inside(tree) {
                case Root(_, entity: Entity) => {
                  val main = (entity.functions collectFirst {
                    case func @ EntFunction(Sym(sym, Nil), _) if sym.name == "main" => func
                  }).get
                  inside(main) {
                    case EntFunction(_, List(StmtExpr(expr))) =>
                      expr should matchPattern { case ExprSym(_) => }
                      TypeAssigner(expr).tpe shouldBe kind
                  }
                }
              }
            }
          }
        }

        "types" - {
          for {
            (expr, kind) <- List(
              ("bool", TypeUInt(1)),
              ("u2", TypeUInt(2)),
              ("uint(3)", TypeUInt(3)),
              ("uint(3)[6]", TypeVector(TypeUInt(3), 6)),
              ("i2", TypeSInt(2)),
              ("int(3)", TypeSInt(3)),
              ("int(3)[6]", TypeVector(TypeSInt(3), 6)),
              ("void", TypeVoid),
              ("t /* typedef */", TypeUInt(4)),
              ("s /* struct */", TypeStruct("s", List("b", "c"), List(TypeUInt(1), TypeSInt(8))))
            )
          } {
            expr in {
              val root = s"""|typedef u4 t;
                             |
                             |struct s {
                             |  bool b;
                             |  i8 c;
                             |}
                             |
                             |fsm thing {
                             |  void main() {
                             |    @bits(${expr});
                             |  }
                             |}""".stripMargin.asTree[Root]

              val tree = xform(root)

              val result = (tree collectFirst {
                case EntFunction(_, List(StmtExpr(e))) => e
              }).value
              val ExprCall(_, List(arg)) = result
              assignChildren(arg)
              TypeAssigner(arg).tpe shouldBe TypeType(kind)
            }
          }
        }

      }

      "unary operators" - {
        for {
          (expr, kind) <- List(
            ("+(32'd1)", TypeUInt(32)),
            ("-(32'd1)", TypeUInt(32)),
            ("~(32'd1)", TypeUInt(32)),
            ("!(32'd1)", TypeUInt(1)),
            ("&(32'd1)", TypeUInt(1)),
            ("|(32'd1)", TypeUInt(1)),
            ("^(32'd1)", TypeUInt(1)),
            ("+(32'sd1)", TypeSInt(32)),
            ("-(32'sd1)", TypeSInt(32)),
            ("~(32'sd1)", TypeSInt(32)),
            ("!(32'sd1)", TypeUInt(1)),
            ("&(32'sd1)", TypeUInt(1)),
            ("|(32'sd1)", TypeUInt(1)),
            ("^(32'sd1)", TypeUInt(1)),
            // unsized
            ("+(1)", TypeNum(false)),
            ("-(1)", TypeNum(false)),
            ("~(1)", TypeNum(false)),
            ("!(1)", TypeUInt(1)),
            ("+(1s)", TypeNum(true)),
            ("-(1s)", TypeNum(true)),
            ("~(1s)", TypeNum(true)),
            ("!(1s)", TypeUInt(1))
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            text.asTree[Expr] match {
              case expr @ ExprUnary(_, operand) =>
                TypeAssigner(operand)
                TypeAssigner(expr).tpe shouldBe kind
                cc.messages shouldBe empty
              case _ => fail()
            }
          }
        }
      }

      "binary operators" - {
        for {
          (src, kind) <- List(
            //
            // sized - sized
            //
            // unsigned - unsigned
            ("32'd1 *   32'd1", TypeUInt(32)),
            ("32'd1 /   32'd1", TypeUInt(32)),
            ("32'd1 %   32'd1", TypeUInt(32)),
            ("32'd1 +   32'd1", TypeUInt(32)),
            ("32'd1 -   32'd1", TypeUInt(32)),
            ("32'd1 <<  32'd1", TypeUInt(32)),
            ("32'd1 >>  32'd1", TypeUInt(32)),
            ("32'd1 >>> 32'd1", TypeUInt(32)),
            ("32'd1 <<< 32'd1", TypeUInt(32)),
            ("32'd1 >   32'd1", TypeUInt(1)),
            ("32'd1 >=  32'd1", TypeUInt(1)),
            ("32'd1 <   32'd1", TypeUInt(1)),
            ("32'd1 <=  32'd1", TypeUInt(1)),
            ("32'd1 ==  32'd1", TypeUInt(1)),
            ("32'd1 !=  32'd1", TypeUInt(1)),
            ("32'd1 &   32'd1", TypeUInt(32)),
            ("32'd1 ^   32'd1", TypeUInt(32)),
            ("32'd1 |   32'd1", TypeUInt(32)),
            ("32'd1 &&  32'd1", TypeUInt(1)),
            ("32'd1 ||  32'd1", TypeUInt(1)),
            // signed - unsigned
            ("32'd1 *   32'sd1", TypeUInt(32)),
            ("32'd1 /   32'sd1", TypeUInt(32)),
            ("32'd1 %   32'sd1", TypeUInt(32)),
            ("32'd1 +   32'sd1", TypeUInt(32)),
            ("32'd1 -   32'sd1", TypeUInt(32)),
            ("32'd1 <<  32'sd1", TypeUInt(32)),
            ("32'd1 >>  32'sd1", TypeUInt(32)),
            ("32'd1 >>> 32'sd1", TypeUInt(32)),
            ("32'd1 <<< 32'sd1", TypeUInt(32)),
            ("32'd1 >   32'sd1", TypeUInt(1)),
            ("32'd1 >=  32'sd1", TypeUInt(1)),
            ("32'd1 <   32'sd1", TypeUInt(1)),
            ("32'd1 <=  32'sd1", TypeUInt(1)),
            ("32'd1 ==  32'sd1", TypeUInt(1)),
            ("32'd1 !=  32'sd1", TypeUInt(1)),
            ("32'd1 &   32'sd1", TypeUInt(32)),
            ("32'd1 ^   32'sd1", TypeUInt(32)),
            ("32'd1 |   32'sd1", TypeUInt(32)),
            ("32'd1 &&  32'sd1", TypeUInt(1)),
            ("32'd1 ||  32'sd1", TypeUInt(1)),
            // signed - unsigned
            ("32'sd1 *   32'd1", TypeUInt(32)),
            ("32'sd1 /   32'd1", TypeUInt(32)),
            ("32'sd1 %   32'd1", TypeUInt(32)),
            ("32'sd1 +   32'd1", TypeUInt(32)),
            ("32'sd1 -   32'd1", TypeUInt(32)),
            ("32'sd1 <<  32'd1", TypeSInt(32)),
            ("32'sd1 >>  32'd1", TypeSInt(32)),
            ("32'sd1 >>> 32'd1", TypeSInt(32)),
            ("32'sd1 <<< 32'd1", TypeSInt(32)),
            ("32'sd1 >   32'd1", TypeUInt(1)),
            ("32'sd1 >=  32'd1", TypeUInt(1)),
            ("32'sd1 <   32'd1", TypeUInt(1)),
            ("32'sd1 <=  32'd1", TypeUInt(1)),
            ("32'sd1 ==  32'd1", TypeUInt(1)),
            ("32'sd1 !=  32'd1", TypeUInt(1)),
            ("32'sd1 &   32'd1", TypeUInt(32)),
            ("32'sd1 ^   32'd1", TypeUInt(32)),
            ("32'sd1 |   32'd1", TypeUInt(32)),
            ("32'sd1 &&  32'd1", TypeUInt(1)),
            ("32'sd1 ||  32'd1", TypeUInt(1)),
            // signed - signed
            ("32'sd1 *   32'sd1", TypeSInt(32)),
            ("32'sd1 /   32'sd1", TypeSInt(32)),
            ("32'sd1 %   32'sd1", TypeSInt(32)),
            ("32'sd1 +   32'sd1", TypeSInt(32)),
            ("32'sd1 -   32'sd1", TypeSInt(32)),
            ("32'sd1 <<  32'sd1", TypeSInt(32)),
            ("32'sd1 >>  32'sd1", TypeSInt(32)),
            ("32'sd1 >>> 32'sd1", TypeSInt(32)),
            ("32'sd1 <<< 32'sd1", TypeSInt(32)),
            ("32'sd1 >   32'sd1", TypeUInt(1)),
            ("32'sd1 >=  32'sd1", TypeUInt(1)),
            ("32'sd1 <   32'sd1", TypeUInt(1)),
            ("32'sd1 <=  32'sd1", TypeUInt(1)),
            ("32'sd1 ==  32'sd1", TypeUInt(1)),
            ("32'sd1 !=  32'sd1", TypeUInt(1)),
            ("32'sd1 &   32'sd1", TypeSInt(32)),
            ("32'sd1 ^   32'sd1", TypeSInt(32)),
            ("32'sd1 |   32'sd1", TypeSInt(32)),
            ("32'sd1 &&  32'sd1", TypeUInt(1)),
            ("32'sd1 ||  32'sd1", TypeUInt(1)),
            //
            // sized - unsized
            //
            // unsigned - unsigned
            ("32'd1 *   1", TypeUInt(32)),
            ("32'd1 /   1", TypeUInt(32)),
            ("32'd1 %   1", TypeUInt(32)),
            ("32'd1 +   1", TypeUInt(32)),
            ("32'd1 -   1", TypeUInt(32)),
            ("32'd1 <<  1", TypeUInt(32)),
            ("32'd1 >>  1", TypeUInt(32)),
            ("32'd1 >>> 1", TypeUInt(32)),
            ("32'd1 <<< 1", TypeUInt(32)),
            ("32'd1 >   1", TypeUInt(1)),
            ("32'd1 >=  1", TypeUInt(1)),
            ("32'd1 <   1", TypeUInt(1)),
            ("32'd1 <=  1", TypeUInt(1)),
            ("32'd1 ==  1", TypeUInt(1)),
            ("32'd1 !=  1", TypeUInt(1)),
            ("32'd1 &   1", TypeUInt(32)),
            ("32'd1 ^   1", TypeUInt(32)),
            ("32'd1 |   1", TypeUInt(32)),
            ("32'd1 &&  1", TypeUInt(1)),
            ("32'd1 ||  1", TypeUInt(1)),
            // unsigned - signed
            ("32'd1 *   1s", TypeUInt(32)),
            ("32'd1 /   1s", TypeUInt(32)),
            ("32'd1 %   1s", TypeUInt(32)),
            ("32'd1 +   1s", TypeUInt(32)),
            ("32'd1 -   1s", TypeUInt(32)),
            ("32'd1 <<  1s", TypeUInt(32)),
            ("32'd1 >>  1s", TypeUInt(32)),
            ("32'd1 >>> 1s", TypeUInt(32)),
            ("32'd1 <<< 1s", TypeUInt(32)),
            ("32'd1 >   1s", TypeUInt(1)),
            ("32'd1 >=  1s", TypeUInt(1)),
            ("32'd1 <   1s", TypeUInt(1)),
            ("32'd1 <=  1s", TypeUInt(1)),
            ("32'd1 ==  1s", TypeUInt(1)),
            ("32'd1 !=  1s", TypeUInt(1)),
            ("32'd1 &   1s", TypeUInt(32)),
            ("32'd1 ^   1s", TypeUInt(32)),
            ("32'd1 |   1s", TypeUInt(32)),
            ("32'd1 &&  1s", TypeUInt(1)),
            ("32'd1 ||  1s", TypeUInt(1)),
            // signed - unsigned
            ("32'sd1 *   1", TypeUInt(32)),
            ("32'sd1 /   1", TypeUInt(32)),
            ("32'sd1 %   1", TypeUInt(32)),
            ("32'sd1 +   1", TypeUInt(32)),
            ("32'sd1 -   1", TypeUInt(32)),
            ("32'sd1 <<  1", TypeSInt(32)),
            ("32'sd1 >>  1", TypeSInt(32)),
            ("32'sd1 >>> 1", TypeSInt(32)),
            ("32'sd1 <<< 1", TypeSInt(32)),
            ("32'sd1 >   1", TypeUInt(1)),
            ("32'sd1 >=  1", TypeUInt(1)),
            ("32'sd1 <   1", TypeUInt(1)),
            ("32'sd1 <=  1", TypeUInt(1)),
            ("32'sd1 ==  1", TypeUInt(1)),
            ("32'sd1 !=  1", TypeUInt(1)),
            ("32'sd1 &   1", TypeUInt(32)),
            ("32'sd1 ^   1", TypeUInt(32)),
            ("32'sd1 |   1", TypeUInt(32)),
            ("32'sd1 &&  1", TypeUInt(1)),
            ("32'sd1 ||  1", TypeUInt(1)),
            // signed - signed
            ("32'sd1 *   1s", TypeSInt(32)),
            ("32'sd1 /   1s", TypeSInt(32)),
            ("32'sd1 %   1s", TypeSInt(32)),
            ("32'sd1 +   1s", TypeSInt(32)),
            ("32'sd1 -   1s", TypeSInt(32)),
            ("32'sd1 <<  1s", TypeSInt(32)),
            ("32'sd1 >>  1s", TypeSInt(32)),
            ("32'sd1 >>> 1s", TypeSInt(32)),
            ("32'sd1 <<< 1s", TypeSInt(32)),
            ("32'sd1 >   1s", TypeUInt(1)),
            ("32'sd1 >=  1s", TypeUInt(1)),
            ("32'sd1 <   1s", TypeUInt(1)),
            ("32'sd1 <=  1s", TypeUInt(1)),
            ("32'sd1 ==  1s", TypeUInt(1)),
            ("32'sd1 !=  1s", TypeUInt(1)),
            ("32'sd1 &   1s", TypeSInt(32)),
            ("32'sd1 ^   1s", TypeSInt(32)),
            ("32'sd1 |   1s", TypeSInt(32)),
            ("32'sd1 &&  1s", TypeUInt(1)),
            ("32'sd1 ||  1s", TypeUInt(1)),
            //
            // unsized - sized
            //
            // unsigned - unsigned
            ("1 *   32'd1", TypeUInt(32)),
            ("1 /   32'd1", TypeUInt(32)),
            ("1 %   32'd1", TypeUInt(32)),
            ("1 +   32'd1", TypeUInt(32)),
            ("1 -   32'd1", TypeUInt(32)),
            ("1 <<  32'd1", TypeNum(false)),
            ("1 >>  32'd1", TypeNum(false)),
            ("1 >>> 32'd1", TypeNum(false)),
            ("1 <<< 32'd1", TypeNum(false)),
            ("1 >   32'd1", TypeUInt(1)),
            ("1 >=  32'd1", TypeUInt(1)),
            ("1 <   32'd1", TypeUInt(1)),
            ("1 <=  32'd1", TypeUInt(1)),
            ("1 ==  32'd1", TypeUInt(1)),
            ("1 !=  32'd1", TypeUInt(1)),
            ("1 &   32'd1", TypeUInt(32)),
            ("1 ^   32'd1", TypeUInt(32)),
            ("1 |   32'd1", TypeUInt(32)),
            ("1 &&  32'd1", TypeUInt(1)),
            ("1 ||  32'd1", TypeUInt(1)),
            // signed - unsigned
            ("1 *   32'sd1", TypeUInt(32)),
            ("1 /   32'sd1", TypeUInt(32)),
            ("1 %   32'sd1", TypeUInt(32)),
            ("1 +   32'sd1", TypeUInt(32)),
            ("1 -   32'sd1", TypeUInt(32)),
            ("1 <<  32'sd1", TypeNum(false)),
            ("1 >>  32'sd1", TypeNum(false)),
            ("1 >>> 32'sd1", TypeNum(false)),
            ("1 <<< 32'sd1", TypeNum(false)),
            ("1 >   32'sd1", TypeUInt(1)),
            ("1 >=  32'sd1", TypeUInt(1)),
            ("1 <   32'sd1", TypeUInt(1)),
            ("1 <=  32'sd1", TypeUInt(1)),
            ("1 ==  32'sd1", TypeUInt(1)),
            ("1 !=  32'sd1", TypeUInt(1)),
            ("1 &   32'sd1", TypeUInt(32)),
            ("1 ^   32'sd1", TypeUInt(32)),
            ("1 |   32'sd1", TypeUInt(32)),
            ("1 &&  32'sd1", TypeUInt(1)),
            ("1 ||  32'sd1", TypeUInt(1)),
            // signed - unsigned
            ("1s *   32'd1", TypeUInt(32)),
            ("1s /   32'd1", TypeUInt(32)),
            ("1s %   32'd1", TypeUInt(32)),
            ("1s +   32'd1", TypeUInt(32)),
            ("1s -   32'd1", TypeUInt(32)),
            ("1s <<  32'd1", TypeNum(true)),
            ("1s >>  32'd1", TypeNum(true)),
            ("1s >>> 32'd1", TypeNum(true)),
            ("1s <<< 32'd1", TypeNum(true)),
            ("1s >   32'd1", TypeUInt(1)),
            ("1s >=  32'd1", TypeUInt(1)),
            ("1s <   32'd1", TypeUInt(1)),
            ("1s <=  32'd1", TypeUInt(1)),
            ("1s ==  32'd1", TypeUInt(1)),
            ("1s !=  32'd1", TypeUInt(1)),
            ("1s &   32'd1", TypeUInt(32)),
            ("1s ^   32'd1", TypeUInt(32)),
            ("1s |   32'd1", TypeUInt(32)),
            ("1s &&  32'd1", TypeUInt(1)),
            ("1s ||  32'd1", TypeUInt(1)),
            // signed - signed
            ("1s *   32'sd1", TypeSInt(32)),
            ("1s /   32'sd1", TypeSInt(32)),
            ("1s %   32'sd1", TypeSInt(32)),
            ("1s +   32'sd1", TypeSInt(32)),
            ("1s -   32'sd1", TypeSInt(32)),
            ("1s <<  32'sd1", TypeNum(true)),
            ("1s >>  32'sd1", TypeNum(true)),
            ("1s >>> 32'sd1", TypeNum(true)),
            ("1s <<< 32'sd1", TypeNum(true)),
            ("1s >   32'sd1", TypeUInt(1)),
            ("1s >=  32'sd1", TypeUInt(1)),
            ("1s <   32'sd1", TypeUInt(1)),
            ("1s <=  32'sd1", TypeUInt(1)),
            ("1s ==  32'sd1", TypeUInt(1)),
            ("1s !=  32'sd1", TypeUInt(1)),
            ("1s &   32'sd1", TypeSInt(32)),
            ("1s ^   32'sd1", TypeSInt(32)),
            ("1s |   32'sd1", TypeSInt(32)),
            ("1s &&  32'sd1", TypeUInt(1)),
            ("1s ||  32'sd1", TypeUInt(1)),
            //
            // unsized - unsized
            //
            // unsigned - unsigned
            ("1 *   1", TypeNum(false)),
            ("1 /   1", TypeNum(false)),
            ("1 %   1", TypeNum(false)),
            ("1 +   1", TypeNum(false)),
            ("1 -   1", TypeNum(false)),
            ("1 <<  1", TypeNum(false)),
            ("1 >>  1", TypeNum(false)),
            ("1 >>> 1", TypeNum(false)),
            ("1 <<< 1", TypeNum(false)),
            ("1 >   1", TypeUInt(1)),
            ("1 >=  1", TypeUInt(1)),
            ("1 <   1", TypeUInt(1)),
            ("1 <=  1", TypeUInt(1)),
            ("1 ==  1", TypeUInt(1)),
            ("1 !=  1", TypeUInt(1)),
            ("1 &   1", TypeNum(false)),
            ("1 ^   1", TypeNum(false)),
            ("1 |   1", TypeNum(false)),
            ("1 &&  1", TypeUInt(1)),
            ("1 ||  1", TypeUInt(1)),
            // unsigned - signed
            ("1 *   1s", TypeNum(false)),
            ("1 /   1s", TypeNum(false)),
            ("1 %   1s", TypeNum(false)),
            ("1 +   1s", TypeNum(false)),
            ("1 -   1s", TypeNum(false)),
            ("1 <<  1s", TypeNum(false)),
            ("1 >>  1s", TypeNum(false)),
            ("1 >>> 1s", TypeNum(false)),
            ("1 <<< 1s", TypeNum(false)),
            ("1 >   1s", TypeUInt(1)),
            ("1 >=  1s", TypeUInt(1)),
            ("1 <   1s", TypeUInt(1)),
            ("1 <=  1s", TypeUInt(1)),
            ("1 ==  1s", TypeUInt(1)),
            ("1 !=  1s", TypeUInt(1)),
            ("1 &   1s", TypeNum(false)),
            ("1 ^   1s", TypeNum(false)),
            ("1 |   1s", TypeNum(false)),
            ("1 &&  1s", TypeUInt(1)),
            ("1 ||  1s", TypeUInt(1)),
            // signed - unsigned
            ("1s *   1", TypeNum(false)),
            ("1s /   1", TypeNum(false)),
            ("1s %   1", TypeNum(false)),
            ("1s +   1", TypeNum(false)),
            ("1s -   1", TypeNum(false)),
            ("1s <<  1", TypeNum(true)),
            ("1s >>  1", TypeNum(true)),
            ("1s >>> 1", TypeNum(true)),
            ("1s <<< 1", TypeNum(true)),
            ("1s >   1", TypeUInt(1)),
            ("1s >=  1", TypeUInt(1)),
            ("1s <   1", TypeUInt(1)),
            ("1s <=  1", TypeUInt(1)),
            ("1s ==  1", TypeUInt(1)),
            ("1s !=  1", TypeUInt(1)),
            ("1s &   1", TypeNum(false)),
            ("1s ^   1", TypeNum(false)),
            ("1s |   1", TypeNum(false)),
            ("1s &&  1", TypeUInt(1)),
            ("1s ||  1", TypeUInt(1)),
            // signed - signed
            ("1s *   1s", TypeNum(true)),
            ("1s /   1s", TypeNum(true)),
            ("1s %   1s", TypeNum(true)),
            ("1s +   1s", TypeNum(true)),
            ("1s -   1s", TypeNum(true)),
            ("1s <<  1s", TypeNum(true)),
            ("1s >>  1s", TypeNum(true)),
            ("1s >>> 1s", TypeNum(true)),
            ("1s <<< 1s", TypeNum(true)),
            ("1s >   1s", TypeUInt(1)),
            ("1s >=  1s", TypeUInt(1)),
            ("1s <   1s", TypeUInt(1)),
            ("1s <=  1s", TypeUInt(1)),
            ("1s ==  1s", TypeUInt(1)),
            ("1s !=  1s", TypeUInt(1)),
            ("1s &   1s", TypeNum(true)),
            ("1s ^   1s", TypeNum(true)),
            ("1s |   1s", TypeNum(true)),
            ("1s &&  1s", TypeUInt(1)),
            ("1s ||  1s", TypeUInt(1)),
            //
            // Mixed width operators
            //
            // unsigned - unsigned
            ("3'd1 <<  32'd1", TypeUInt(3)),
            ("3'd1 >>  32'd1", TypeUInt(3)),
            ("3'd1 >>> 32'd1", TypeUInt(3)),
            ("3'd1 <<< 32'd1", TypeUInt(3)),
            ("3'd1 &&  32'd1", TypeUInt(1)),
            ("3'd1 ||  32'd1", TypeUInt(1)),
            // unsigned - signed
            ("3'd1 <<  32'sd1", TypeUInt(3)),
            ("3'd1 >>  32'sd1", TypeUInt(3)),
            ("3'd1 >>> 32'sd1", TypeUInt(3)),
            ("3'd1 <<< 32'sd1", TypeUInt(3)),
            ("3'd1 &&  32'sd1", TypeUInt(1)),
            ("3'd1 ||  32'sd1", TypeUInt(1)),
            // igned - unsigned
            ("3'sd1 <<  32'd1", TypeSInt(3)),
            ("3'sd1 >>  32'd1", TypeSInt(3)),
            ("3'sd1 >>> 32'd1", TypeSInt(3)),
            ("3'sd1 <<< 32'd1", TypeSInt(3)),
            ("3'sd1 &&  32'd1", TypeUInt(1)),
            ("3'sd1 ||  32'd1", TypeUInt(1)),
            // signed - signed
            ("3'sd1 <<  32'sd1", TypeSInt(3)),
            ("3'sd1 >>  32'sd1", TypeSInt(3)),
            ("3'sd1 >>> 32'sd1", TypeSInt(3)),
            ("3'sd1 <<< 32'sd1", TypeSInt(3)),
            ("3'sd1 &&  32'sd1", TypeUInt(1)),
            ("3'sd1 ||  32'sd1", TypeUInt(1))
          )
        } {
          src in {
            src.asTree[Expr] match {
              case expr @ ExprBinary(lhs, _, rhs) =>
                TypeAssigner(lhs)
                TypeAssigner(rhs)
                TypeAssigner(expr).tpe shouldBe kind
                cc.messages shouldBe empty
              case _ => fail()
            }
          }
        }
      }

      "ternary operator" - {
        for {
          (src, kind) <- List(
            // sized - sized
            ("1 ?  5'd2 :  5'd3", TypeUInt(5)),
            ("1 ?  5'd2 : 5'sd3", TypeUInt(5)),
            ("1 ? 5'sd2 :  5'd3", TypeUInt(5)),
            ("1 ? 5'sd2 : 5'sd3", TypeSInt(5)),
            // sized - unsized
            ("1 ?  5'd2 : 3u", TypeUInt(5)),
            ("1 ?  5'd2 : 3s", TypeUInt(5)),
            ("1 ? 5'sd2 : 3u", TypeUInt(5)),
            ("1 ? 5'sd2 : 3s", TypeSInt(5)),
            // unsized - sized
            ("1 ? 2u :  5'd3", TypeUInt(5)),
            ("1 ? 2u : 5'sd3", TypeUInt(5)),
            ("1 ? 2s :  5'd3", TypeUInt(5)),
            ("1 ? 2s : 5'sd3", TypeSInt(5)),
            // unsized - unsized
            ("1 ? 2u : 3u", TypeNum(false)),
            ("1 ? 2u : 3s", TypeNum(false)),
            ("1 ? 2s : 3u", TypeNum(false)),
            ("1 ? 2s : 3s", TypeNum(true))
          )
        } {
          src in {
            src.asTree[Expr] match {
              case expr @ ExprTernary(cond, thenExpr, elseExpr) =>
                TypeAssigner(cond)
                TypeAssigner(thenExpr)
                TypeAssigner(elseExpr)
                TypeAssigner(expr).tpe shouldBe kind
                cc.messages shouldBe empty
              case _ => fail()
            }
          }
        }
      }

      "cat" - {
        for {
          (expr, kind) <- List(
            ("{  3'd0,  4'd0 }", TypeUInt(7)),
            ("{  3'd0, 4'sd0 }", TypeUInt(7)),
            ("{ 3'sd0,  4'd0 }", TypeUInt(7)),
            ("{ 3'sd0, 4'sd0 }", TypeUInt(7))
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            text.asTree[Expr] match {
              case expr @ ExprCat(parts) =>
                parts foreach { TypeAssigner(_) }
                TypeAssigner(expr).tpe shouldBe kind
                cc.messages shouldBe empty
              case _ => fail()
            }
          }
        }
      }

      "rep" - {
        for {
          (expr, kind) <- List(
            ("{1{4'd0}}", TypeUInt(4)),
            ("{2{4'd0}}", TypeUInt(8)),
            ("{3{4'd0}}", TypeUInt(12)),
            ("{1{4'sd0}}", TypeUInt(4)),
            ("{2{4'sd0}}", TypeUInt(8)),
            ("{3{4'sd0}}", TypeUInt(12))
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            val expr = text.asTree[Expr]
            assignChildren(expr)
            TypeAssigner(expr).tpe shouldBe kind
            cc.messages shouldBe empty
          }
        }
      }

      "index" - {
        for {
          (expr, kind) <- List(
            ("a[0]", TypeUInt(1)),
            ("a[1]", TypeUInt(1)),
            ("b[0]", TypeSInt(7)),
            ("b[1]", TypeSInt(7)),
            ("b[0][0]", TypeUInt(1)),
            ("b[0][1]", TypeUInt(1)),
            ("c[0]", TypeVector(TypeSInt(7), 2)),
            ("c[1]", TypeVector(TypeSInt(7), 2)),
            ("c[0][0]", TypeSInt(7)),
            ("c[0][1]", TypeSInt(7)),
            ("c[0][0][0]", TypeUInt(1)),
            ("c[0][0][1]", TypeUInt(1)),
            ("d[0]", TypeSInt(7)),
            ("d[1]", TypeSInt(7)),
            ("d[0][0]", TypeUInt(1)),
            ("d[0][1]", TypeUInt(1)),
            ("e[0]", TypeSInt(7)),
            ("e[1]", TypeSInt(7)),
            ("e[0][0]", TypeUInt(1)),
            ("e[0][1]", TypeUInt(1)),
            ("f[0]", TypeVector(TypeVector(TypeSInt(7), 2), 4)),
            ("f[1]", TypeVector(TypeVector(TypeSInt(7), 2), 4)),
            ("f[0][0]", TypeVector(TypeSInt(7), 2)),
            ("f[0][1]", TypeVector(TypeSInt(7), 2)),
            ("f[0][0][0]", TypeSInt(7)),
            ("f[0][0][1]", TypeSInt(7)),
            ("f[0][0][0][0]", TypeUInt(1)),
            ("f[0][0][0][1]", TypeUInt(1)),
            ("g[0]", TypeUInt(1)),
            ("g[1]", TypeUInt(1)),
            ("1[0]", TypeUInt(1)),
            ("1[1]", TypeUInt(1))
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            val block = s"""|{
                            |  (* unused *) i7 a;
                            |  (* unused *) i7[2] b;
                            |  (* unused *) i7[4][2] c;
                            |  (* unused *) i7 d[2];
                            |  (* unused *) i7 e[2];
                            |  (* unused *) i7[4][2] f[3];
                            |  (* unused *) in i7 g;
                            |
                            |  ${text};
                            |}""".stripMargin.asTree[Stmt]

            val tree = xform(block)

            tree.postOrderIterator collect { case expr: Expr => expr } foreach {
              TypeAssigner(_)
            }

            inside(tree) {
              case StmtBlock(stmts) =>
                inside(stmts.last) {
                  case StmtExpr(e) =>
                    e.tpe shouldBe kind
                }
            }
            cc.messages shouldBe empty
          }
        }
      }

      "slice" - {
        for {
          (expr, kind) <- List(
            ("a[8'd3 :8'd2]", TypeUInt(ExprNum(false, 2))),
            ("a[8'd0 :8'd0]", TypeUInt(ExprNum(false, 1))),
            ("a[8'd4+:8'd3]", TypeUInt(ExprNum(false, 3))),
            ("a[8'd4-:8'd3]", TypeUInt(ExprNum(false, 3))),
            ("1[8'd3 :8'd0]", TypeUInt(ExprNum(false, 4))),
            ("1[8'd3+:8'd2]", TypeUInt(ExprNum(false, 2))),
            ("1[8'd3-:8'd2]", TypeUInt(ExprNum(false, 2))),
            ("a[5'd31:5'd0]", TypeUInt(ExprNum(false, 32))),
            ("b[2'd1 :2'd1]", TypeVector(TypeUInt(32), ExprNum(false, 1))),
            ("b[2'd1 :2'd0]", TypeVector(TypeUInt(32), ExprNum(false, 2))),
            ("b[2'd3 :2'd1]", TypeVector(TypeUInt(32), ExprNum(false, 3))),
            ("b[2'd3 :2'd0]", TypeVector(TypeUInt(32), ExprNum(false, 4))),
            ("b[2'd0+:3'd1]", TypeVector(TypeUInt(32), ExprNum(false, 1))),
            ("b[2'd2+:3'd2]", TypeVector(TypeUInt(32), ExprNum(false, 2))),
            ("b[2'd1+:3'd3]", TypeVector(TypeUInt(32), ExprNum(false, 3))),
            ("b[2'd0+:3'd4]", TypeVector(TypeUInt(32), ExprNum(false, 4))),
            ("b[2'd1-:3'd1]", TypeVector(TypeUInt(32), ExprNum(false, 1))),
            ("b[2'd1-:3'd2]", TypeVector(TypeUInt(32), ExprNum(false, 2))),
            ("b[2'd3-:3'd3]", TypeVector(TypeUInt(32), ExprNum(false, 3))),
            ("b[2'd3-:3'd4]", TypeVector(TypeUInt(32), ExprNum(false, 4)))
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            val block = s"""|{
                            |  (* unused *) u32 a;
                            |  (* unused *) u32[4] b;
                            |
                            |  ${text};
                            |}""".stripMargin.asTree[Stmt]

            val tree = xform(block)
            val expr = tree getFirst { case e: Expr => e }
            assignChildren(expr)
            TypeAssigner(expr).tpe shouldBe kind
            cc.messages shouldBe empty
          }
        }
      }

      "select" - {
        for {
          (expr, kind) <- List(
            ("a.a", TypeSInt(6)),
            ("a.b", TypeStruct("s", List("a", "b"), List(TypeUInt(1), TypeUInt(8)))),
            ("a.b.a", TypeUInt(1)),
            ("a.b.b", TypeUInt(8)),
            ("pi0.valid", TypeUInt(1)),
            ("pi0.read", TypeCombFunc(Nil, TypeSInt(8))),
            ("pi0.wait", TypeCombFunc(Nil, TypeVoid)),
            ("pi1.valid", TypeUInt(1)),
            ("pi1.read", TypeCombFunc(Nil, TypeVoid)),
            ("pi1.wait", TypeCombFunc(Nil, TypeVoid)),
            ("po0.valid", TypeUInt(1)),
            ("po0.write", TypeCombFunc(List(TypeSInt(8)), TypeVoid)),
            ("po0.flush", TypeCombFunc(Nil, TypeVoid)),
            ("po0.full", TypeUInt(1)),
            ("po0.empty", TypeUInt(1)),
            ("po1.valid", TypeUInt(1)),
            ("po1.write", TypeCombFunc(Nil, TypeVoid)),
            ("po1.flush", TypeCombFunc(Nil, TypeVoid)),
            ("po1.full", TypeUInt(1)),
            ("po1.empty", TypeUInt(1))
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            val block = s"""|struct s {
                            |  bool a;
                            |  u8   b;
                            |}
                            |
                            |struct t {
                            |  i6  a;
                            |  s   b;
                            |}
                            |
                            |fsm f {
                            |  in  sync ready i8   pi0;
                            |  in  sync ready void pi1;
                            |  out sync ready i8   po0;
                            |  out sync ready void po1;
                            |
                            |  t a;
                            |
                            |  void main() {
                            |    pi0; pi1; po0; po1; a; // Suppress unused warnings
                            |
                            |    ${text};
                            |  }
                            |}""".stripMargin.asTree[Root]

            val tree = xform(block)

            tree.postOrderIterator collect {
              case expr: ExprSym    => expr
              case expr: ExprSelect => expr
            } foreach {
              TypeAssigner(_)
            }

            val expr = (tree collectFirst { case expr: ExprSelect => expr }).value

            expr.tpe shouldBe kind

            cc.messages shouldBe empty
          }
        }
      }

      "select from type" in {
        val tree = """|struct a {
                      | i8 b;
                      |}
                      |
                      |fsm c {
                      |  void main() {
                      |    @bits(a.b);
                      |  }
                      |}""".stripMargin.asTree[Root]
        val expr = (xform(tree) collectFirst { case e: ExprSelect => e }).value

        expr.postOrderIterator collect {
          case e: Expr => e
        } foreach {
          TypeAssigner(_)
        }

        expr.tpe shouldBe TypeType(TypeSInt(8))

        cc.messages shouldBe empty
      }

      "sized integer literals" - {
        for {
          (expr, kind) <- List(
            ("1'b0", TypeUInt(1)),
            ("2'd1", TypeUInt(2)),
            ("3'sd2", TypeSInt(3)),
            ("4'sd3", TypeSInt(4))
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            val expr = text.asTree[Expr]
            TypeAssigner(expr).tpe shouldBe kind
            cc.messages shouldBe empty
          }
        }
      }

      "unsized integer literals" - {
        for {
          (expr, kind) <- List(
            ("0", TypeNum(false)),
            ("1", TypeNum(false)),
            ("0s", TypeNum(true)),
            ("1s", TypeNum(true))
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            val expr = text.asTree[Expr]
            TypeAssigner(expr).tpe shouldBe kind
            cc.messages shouldBe empty
          }
        }
      }

      "string literals" - {
        for {
          (expr, kind) <- List(
            ("\"abc\"", TypeStr),
            ("\"\"", TypeStr)
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            val expr = text.asTree[Expr]
            TypeAssigner(expr).tpe shouldBe kind
            cc.messages shouldBe empty
          }
        }
      }

      "call" - {
        for {
          (expr, kind) <- List(
            ("pi0.read", TypeSInt(8)),
            ("pi0.wait", TypeVoid),
            ("pi1.read", TypeVoid),
            ("pi1.wait", TypeVoid),
            ("po0.write", TypeVoid),
            ("po0.flush", TypeVoid),
            ("po1.write", TypeVoid),
            ("po1.flush", TypeVoid)
          )
        } {
          val text = expr.trim.replaceAll(" +", " ")
          text in {
            val block = s"""|struct s {
                            |  bool a;
                            |  u8   b;
                            |}
                            |
                            |struct t {
                            |  i6  a;
                            |  s   b;
                            |}
                            |
                            |fsm f {
                            |  in  sync ready i8   pi0;
                            |  in  sync ready void pi1;
                            |  out sync ready i8   po0;
                            |  out sync ready void po1;
                            |
                            |  t a;
                            |
                            |  void main() {
                            |    pi0; pi1; po0; po1; a; // Suppress unused warnings
                            |
                            |    ${text}();
                            |  }
                            |}""".stripMargin.asTree[Root]

            val tree = xform(block)

            tree.postOrderIterator collect {
              case expr: ExprSym    => expr
              case expr: ExprSelect => expr
              case expr: ExprCall   => expr
            } foreach {
              TypeAssigner(_)
            }

            val expr = (tree collectFirst { case expr: ExprCall => expr }).value

            expr.tpe shouldBe kind

            cc.messages shouldBe empty
          }
        }
      }

      "cast" - {
        for {
          (exprSrc, kindSrc, kind) <- List(
            // format: off
            ("32", "u8", TypeUInt(8)),
            ("32s", "i8", TypeSInt(8)),
            ("8'd1", "uint", TypeNum(false)),
            ("8'sd1", "int", TypeNum(true))
            // format: on
          )
        } {
          s"(${kindSrc})(${exprSrc})" in {
            val expr = (xform(exprSrc.asTree[Expr]) rewrite { new Typer }).asInstanceOf[Expr]
            val castKind = kindSrc.asTree[Expr] match {
              case ExprType(kind) => kind
              case _              => fail()
            }
            cc.messages shouldBe empty
            TypeAssigner(ExprCast(castKind, expr) withLoc Loc.synthetic).kind shouldBe kind
            kind shouldBe castKind
          }
        }
      }
    }

    "statements" - {
      "unambiguous comb statements" - {
        for {
          (text, pattern) <- List[(String, PartialFunction[Any, Unit])](
            ("a = a + 1;", { case _: StmtAssign => }),
            ("a++;", { case _: StmtPost         => }),
            ("a += 1;", { case _: StmtUpdate    => }),
            ("bool c;", { case _: StmtDecl      => }),
            ("read;", { case _: StmtRead        => }),
            ("write;", { case _: StmtWrite      => }),
          )
        } {
          text in {
            val tree = s"""|{
                           |  bool a;
                           |  a;
                           |
                           |  ${text}
                           |}""".stripMargin.asTree[Stmt]

            val stmts = xform(tree) match {
              case StmtBlock(s) => s
              case _            => fail()
            }

            inside(stmts.last) {
              case stmt: Stmt =>
                stmt should matchPattern(pattern)
                assignChildren(stmt)
                TypeAssigner(stmt).tpe shouldBe TypeCombStmt
            }
          }
        }
      }

      "unambiguous crtl statements" - {
        for {
          (text, pattern) <- List[(String, PartialFunction[Any, Unit])](
            ("goto a;", { case _: StmtGoto                                          => }),
            ("return;", { case _: StmtReturn                                        => }),
            ("fence;", { case _: StmtFence                                          => }),
            ("break;", { case _: StmtBreak                                          => }),
            ("continue;", { case _: StmtContinue                                    => }),
            ("for(;;) {}", { case _: StmtFor                                        => }),
            ("do {} while(1);", { case _: StmtDo                                    => }),
            ("while (1) {}", { case _: StmtWhile                                    => }),
            ("loop {}", { case _: StmtLoop                                          => }),
            ("let (bool b = 1) for(;;) {}", { case StmtLet(_, List(_: StmtFor))     => }),
            ("let (bool b = 1) do {} while(1);", { case StmtLet(_, List(_: StmtDo)) => }),
            ("let (bool b = 1) while (1) {}", { case StmtLet(_, List(_: StmtWhile)) => }),
            ("let (bool b = 1) loop {}", { case StmtLet(_, List(_: StmtLoop))       => })
          )
        } {
          text in {
            val tree = s"""|{
                           |  bool a;
                           |  a;
                           |
                           |  ${text}
                           |}""".stripMargin.asTree[Stmt]

            val stmts = xform(tree) match {
              case StmtBlock(s) => s
              case _            => fail()
            }

            inside(stmts.last) {
              case stmt: Stmt =>
                stmt should matchPattern(pattern)
                stmt.postOrderIterator foreach { case tree: Tree => TypeAssigner(tree) }
                stmt.tpe shouldBe TypeCtrlStmt
            }
          }
        }
      }

      "context dependent statements" - {
        for {
          (text, pattern, kind) <- List[(String, PartialFunction[Any, Unit], Type)](
            ("if(a) read;", { case StmtIf(_, _, Nil)                  => }, TypeCombStmt),
            ("if(a) read; else write;", { case StmtIf(_, _, _ :: _)   => }, TypeCombStmt),
            ("if(a) fence;", { case StmtIf(_, _, Nil)                 => }, TypeCtrlStmt),
            ("if(a) fence; else return;", { case StmtIf(_, _, _ :: _) => }, TypeCtrlStmt),
            ("case(a) {a: read;}", { case _: StmtCase                 => }, TypeCombStmt),
            ("case(a) {default: read;}", { case _: StmtCase           => }, TypeCombStmt),
            ("case(a) {a: fence;}", { case _: StmtCase                => }, TypeCtrlStmt),
            ("case(a) {default: fence;}", { case _: StmtCase          => }, TypeCtrlStmt),
            ("case(a) {default: {read; fence;}}", { case _: StmtCase  => }, TypeCtrlStmt),
            ("a;", { case StmtExpr(_: ExprSym)                        => }, TypeCombStmt),
            ("a + a;", { case StmtExpr(_: ExprBinary)                 => }, TypeCombStmt),
            ("a.read();", { case StmtExpr(_: ExprCall)                => }, TypeCombStmt),
            ("main();", { case StmtExpr(_: ExprCall)                  => }, TypeCtrlStmt),
            ("{ }", { case _: StmtBlock                               => }, TypeCombStmt),
            ("{ a; fence; }", { case _: StmtBlock                     => }, TypeCtrlStmt),
            ("{ a; a; }", { case _: StmtBlock                         => }, TypeCombStmt)
          )
        } {
          text in {
            val entity = s"""|fsm a {
                             |  in sync bool a;
                             |
                             |  void main() {
                             |    a;
                             |    ${text}
                             |  }
                             |}""".stripMargin.asTree[Entity]

            val tree = xform(entity)

            val stmt = tree getFirst { case EntFunction(_, stmts) => stmts.last }

            stmt.postOrderIterator collect {
              case node: Stmt => node
              case node: Case => node
              case node: Expr => node
            } foreach {
              TypeAssigner(_)
            }

            inside(stmt) {
              case stmt: Stmt =>
                stmt should matchPattern(pattern)
                stmt.tpe shouldBe kind
            }
          }
        }
      }
    }

    "entity contents" - {
      for {
        (name, text, pattern) <- List[(String, String, PartialFunction[Any, Tree])](
          ("entity", "fsm e {}", { case c: EntEntity => c }),
          ("decl", "param bool e = true;", {
            case c @ EntDecl(Decl(symbol, _)) if symbol.kind.isInstanceOf[TypeParam] => c
          }),
          ("instance", "d = new a();", { case c: EntInstance   => c }),
          ("connect", "b -> c;", { case c: EntConnect          => c }),
          ("function", "void main() {}", { case c: EntFunction => c })
        )
      } {
        name in {
          val entity = s"""|network a {
                           |  in bool b;
                           |  out bool c;
                           |  ${text}
                           |}""".stripMargin.asTree[Entity]

          val contents = (xform(entity).children collect pattern).toList

          contents foreach assignChildren

          TypeAssigner(contents.loneElement).tpe shouldBe TypeMisc
        }
      }

      "state" in {
        val ref = TypeAssigner(ExprSym(ErrorSymbol))
        TypeAssigner(EntState(ref, Nil)).tpe shouldBe TypeMisc
      }
    }

    "Sym" in {
      val symbol = cc.newTermSymbol("foo", Loc.synthetic, TypeUInt(4))
      TypeAssigner(Sym(symbol, Nil)).tpe shouldBe TypeUInt(4)
    }
  }
}
