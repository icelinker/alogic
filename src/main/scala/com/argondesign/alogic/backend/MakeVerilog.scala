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
// Verilog backend
////////////////////////////////////////////////////////////////////////////////
package com.argondesign.alogic.backend

import alogic.backend.CodeWriter
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.Symbols._
import com.argondesign.alogic.core.Types._
import com.argondesign.alogic.core.enums.ResetStyle
import com.argondesign.alogic.util.unreachable

import scala.collection.mutable.ListBuffer

final class MakeVerilog(
    presentDetails: EntityDetails,
    details: => Map[TypeSymbol, EntityDetails]
)(
    implicit cc: CompilerContext
) {

  import presentDetails._

  // Render Verilog declaration List(signed, packed, id) strings for term symbol
  private def vdecl(symbol: TermSymbol): String = {
    def decl(id: String, kind: Type): String = {
      kind match {
        case intKind: TypeInt => {
          val signedStr = if (kind.isSigned) "signed " else ""
          intKind.width match {
            case 1 => s"${signedStr}${id}"
            case n => s"${signedStr}[${n - 1}:0] ${id}"
          }
        }
        case TypeArray(elemKind, sizeExpr) => {
          s"${decl(id, elemKind)} [${sizeExpr.value.get - 1}:0]"
        }
        case _ => cc.ice(s"Don't know how to declare this type in Verilog:", kind.toString)
      }
    }

    decl(symbol.name, symbol.kind.underlying)
  }

  // Render expression to Verilog
  private def vexpr(expr: Expr, wrapCat: Boolean, indent: Int): String = {
    lazy val i0 = "  "
    lazy val i = i0 * indent
    def loop(expr: Expr) = {
      expr match {
        case ExprCall(e, args)         => s"${vexpr(e)}(${args map vexpr mkString ", "})"
        case ExprUnary(op, e)          => s"(${op}${vexpr(e)})"
        case ExprBinary(l, op, r)      => s"(${vexpr(l)} ${op} ${vexpr(r)})"
        case ExprTernary(cond, te, ee) => s"(${vexpr(cond)} ? ${vexpr(te)} : ${vexpr(ee)})"
        case ExprRep(count, e)         => s"{${vexpr(count)}{${vexpr(e)}}}"
        case ExprCat(parts) => {
          if (wrapCat) {
            parts map vexpr mkString (s"{\n${i0}${i}", s",\n${i0}${i}", s"\n${i}}")
          } else {
            parts map vexpr mkString ("{", ", ", "}")
          }
        }
        case ExprIndex(e, index)           => s"${vexpr(e)}[${vexpr(index)}]"
        case ExprSlice(e, lidx, op, ridx)  => s"${vexpr(e)}[${vexpr(lidx)}${op}${vexpr(ridx)}]"
        case ExprSelect(e, sel, Nil)       => s"${vexpr(e)}${cc.sep}${sel}"
        case ExprSym(symbol)               => symbol.name
        case ExprInt(false, w, v)          => s"${w}'d${v}"
        case ExprInt(true, w, v) if v >= 0 => s"${w}'sd${v}"
        case ExprInt(true, w, v)           => s"-${w}'sd${-v}"
        case ExprNum(false, value)         => s"'d${value}"
        case ExprNum(true, value)          => s"${value}"
        case ExprStr(str)                  => s""""${str}""""
        case _ => {
          cc.ice(expr, "Don't know how to emit Verilog for expression", expr.toString)
        }
      }
    }

    loop(expr)
  }

  private def vexpr(expr: Expr): String = vexpr(expr, wrapCat = false, indent = 0)

  //////////////////////////////////////////////////////////////////////////////
  // Emit code
  //////////////////////////////////////////////////////////////////////////////

  private def emitDeclarationSection(body: CodeWriter): Unit = {
    if (hasConsts || hasFlops || hasCombSignals || hasArrays || hasInterconnect || canStall) {
      body.emitSection(1, "Declaration section") {
        if (hasConsts) {
          // Emit const declarations
          body.emitBlock(1, "Local parameter declarations") {
            entity.declarations collect {
              case Decl(symbol, Some(init)) if symbol.kind.isInstanceOf[TypeConst] => {
                (symbol, init)
              }
            } foreach {
              case (symbol, init) =>
                body.emit(1)(s"localparam ${vdecl(symbol)} = ${vexpr(init)};")
            }
          }
        }

        def emitVarDecl(symbol: TermSymbol) = {
          if (netSymbols contains symbol) {
            body.emit(1)(s"wire ${vdecl(symbol)};")
          } else {
            body.emit(1)(s"reg ${vdecl(symbol)};")
          }
        }

        if (hasFlops) {
          // Emit flop declarations
          body.emitBlock(1, "Flop declarations") {
            for {
              Decl(qSymbol, _) <- entity.declarations
              dSymbol <- qSymbol.attr.flop.get
            } {
              emitVarDecl(qSymbol)
              emitVarDecl(dSymbol)
            }
          }
        }

        if (hasCombSignals) {
          body.emitBlock(1, "Combinatorial signal declarations") {
            for {
              Decl(symbol, _) <- entity.declarations
              if symbol.attr.combSignal.get contains true
            } {
              emitVarDecl(symbol)
            }
          }
        }

        if (hasArrays) {
          // Emit memory declarations
          body.emitBlock(1, "Memory declarations") {
            for {
              Decl(qSymbol, _) <- entity.declarations
              if qSymbol.attr.memory.isSet
            } yield {
              emitVarDecl(qSymbol)
            }
          }
        }

        if (hasInterconnect) {
          // Emit interconnect declarations
          body.emitBlock(1, "Interconnect declarations") {
            for {
              (iSymbol, nSymbols) <- groupedInterconnectSymbols
            } {
              body.ensureBlankLine()
              body.emit(1)(s"// ${iSymbol.name}")
              nSymbols foreach emitVarDecl
            }
          }
        }

        // Emit go declarations
        if (canStall) {
          body.emit(1)("// go declaration")
          body.emit(1)("reg go;")
          body.ensureBlankLine()
        }
      }
    }
  }

  private def emitInternalStorageSection(body: CodeWriter): Unit = {
    if (hasFlops || hasArrays) {
      body.emitSection(1, "Storage section") {
        if (resetFlops.nonEmpty) {
          body.emitBlock(1, "Reset flops") {
            body.emit(1) {
              cc.settings.resetStyle match {
                case ResetStyle.AsyncLow  => s"always @(posedge clk or negedge ${cc.rst}) begin"
                case ResetStyle.AsyncHigh => s"always @(posedge clk or posedge ${cc.rst}) begin"
                case _                    => "always @(posedge clk) begin"
              }
            }
            body.emit(2)(
              cc.settings.resetStyle match {
                case ResetStyle.AsyncLow | ResetStyle.SyncLow => s"if (!${cc.rst}) begin"
                case _                                        => s"if (${cc.rst}) begin"
              }
            )

            body.emit(3) {
              for {
                Decl(qSymbol, initOpt) <- resetFlops
              } yield {
                val id = qSymbol.name
                val init = initOpt match {
                  case Some(expr) => expr
                  case None       => ExprInt(qSymbol.kind.isSigned, qSymbol.kind.width, 0)
                }
                s"${id} <= ${vexpr(init)};"
              }
            }

            if (canStall) {
              body.emit(2)("end else if (go) begin")
            } else {
              body.emit(2)("end else begin")
            }

            body.emit(3) {
              for {
                Decl(qSymbol, _) <- resetFlops
                dSymbol <- qSymbol.attr.flop.get
              } yield {
                s"${qSymbol.name} <= ${dSymbol.name};"
              }
            }

            body.emit(2)("end")
            body.emit(1)("end")
          }
          body.ensureBlankLine()
        }

        if (unresetFlops.nonEmpty) {
          body.emitBlock(1, "Unreset flops") {
            body.emit(1) {
              "always @(posedge clk) begin"
            }

            if (canStall) {
              body.emit(2)("if (go) begin")
            } else {
              body.emit(2)("begin")
            }

            body.emit(3) {
              for {
                Decl(qSymbol, _) <- unresetFlops
                dSymbol <- qSymbol.attr.flop.get
              } yield {
                s"${qSymbol.name} <= ${dSymbol.name};"
              }
            }

            body.emit(2)("end")
            body.emit(1)("end")
          }
          body.ensureBlankLine()
        }

        if (hasArrays) {
          body.emitBlock(1, "Distributed memory section") {
            for {
              Decl(qSymbol, _) <- entity.declarations
              (weSymbol, waSymbol, wdSymbol) <- qSymbol.attr.memory.get
            } {
              body.emit(1) {
                List(
                  "always @(posedge clk) begin",
                  if (canStall) {
                    s"  if (go && ${weSymbol.name}) begin"
                  } else {
                    s"  if (${weSymbol.name}) begin"
                  },
                  s"    ${qSymbol.name}[${waSymbol.name}] <= ${wdSymbol.name};",
                  "  end",
                  "end"
                )
              }
              body.ensureBlankLine()
            }
          }
        }
      }
    }
  }

  private def emitStatement(body: CodeWriter, indent: Int, stmt: Stmt): Unit = {

    stmt match {
      // Block
      case StmtBlock(stmts) => {
        body.emit(indent)("begin")
        stmts foreach {
          emitStatement(body, indent + 1, _)
        }
        body.emit(indent)("end")
      }

      // If statement
      case StmtIf(cond, thenStmts, elseStmts) => {
        body.emit(indent)(s"if (${vexpr(cond)}) begin")
        thenStmts foreach { emitStatement(body, indent + 1, _) }
        if (elseStmts.nonEmpty) {
          body.emit(indent)(s"end else begin")
          elseStmts foreach { emitStatement(body, indent + 1, _) }
        }
        body.emit(indent)(s"end")
      }

      // Case statement
      case StmtCase(cond, cases) => {
        body.emit(indent)(s"case (${vexpr(cond)})")
        cases foreach {
          case CaseRegular(conds, stmts) => {
            body.emit(indent + 1)(s"${conds map vexpr mkString ", "}: begin")
            stmts foreach { emitStatement(body, indent + 2, _) }
            body.emit(indent + 1)("end")
          }
          case CaseDefault(stmts) => {
            body.emit(indent + 1)("default: begin")
            stmts foreach { emitStatement(body, indent + 2, _) }
            body.emit(indent + 1)("end")
          }
          case _: CaseGen => unreachable
        }
        body.emit(indent)(s"endcase")
      }

      // Assignment statements
      case StmtAssign(lhs, rhs) => {
        def wrapIfCat(expr: Expr) = expr match {
          case _: ExprCat => vexpr(expr, wrapCat = true, indent = indent)
          case _          => vexpr(expr)
        }
        body.emit(indent)(s"${wrapIfCat(lhs)} = ${wrapIfCat(rhs)};")
      }

      // Error statement injected by compiler at an earlier error
      case stmts: StmtError => body.emit(indent)(s"/* Error statement from ${stmt.loc.prefix} */")

      // Stall statement
      case StmtStall(cond) => {
        body.emit(indent)(s"if (!${vexpr(cond)}) begin")
        body.emit(indent + 1)("go = 1'b0;")
        body.emit(indent)(s"end")
      }

      // Expression statements like $display();
      case StmtExpr(expr) => body.emit(indent)(s"${vexpr(expr)};")

      // Comment
      case StmtComment(str) => body.emit(indent)("// " + str)

      case other => cc.ice(other, "Don't know how to emit Verilog for statement", other.toString)
    }
  }

  private def emitCombProcesses(body: CodeWriter): Unit = {
    require(entity.combProcesses.lengthIs <= 1)
    entity.combProcesses foreach {
      case EntCombProcess(stmts) =>
        assert(stmts.nonEmpty)
        body.emitSection(1, "State system") {
          body.emit(1)("always @* begin")

          if (canStall) {
            body.emit(2)("// go default")
            body.emit(2)("go = 1'b1;")
            body.ensureBlankLine()
          }

          stmts foreach { stmt =>
            emitStatement(body, 2, stmt)
          }

          val cSymbols = entity.declarations collect {
            case Decl(symbol, _) if symbol.attr.clearOnStall.get contains true => symbol
          }

          if (cSymbols.nonEmpty && canStall) {
            body.ensureBlankLine()
            body.emit(2)("// Clears")
            body.emit(2)("if (!go) begin")
            for (symbol <- cSymbols) {
              val s = if (symbol.kind.isSigned) "s" else ""
              body.emit(3)(s"${symbol.name} = ${symbol.kind.width}'${s}b0;")
            }
            body.emit(2)("end")
          }

          body.emit(1)("end")
        }
    }
  }

  private def emitInstances(body: CodeWriter): Unit = {
    if (entity.instances.nonEmpty) {
      body.emitSection(1, "Instances") {
        for (EntInstance(Sym(iSymbol: TermSymbol, _), Sym(mSymbol: TypeSymbol, _), _, _) <- entity.instances) {
          val TypeInstance(eSymbol) = iSymbol.kind.asInstance
          body.ensureBlankLine()
          body.emit(1)(s"${eSymbol.name} ${iSymbol.name} (")

          body.emitTable(2, " ") {
            val items = new ListBuffer[List[String]]

            if (details(mSymbol).needsClock) {
              items append { List(".clk", "         (clk),") }
            }
            if (details(mSymbol).needsReset) {
              items append { List(s".${cc.rst}", s"         (${cc.rst}),") }
            }

            val TypeEntity(_, pSymbols, _) = eSymbol.kind.asEntity

            val lastIndex = pSymbols.length - 1

            for ((pSymbol, i) <- pSymbols.zipWithIndex) {
              val dir = pSymbol.kind match {
                case _: TypeIn => "<-"
                case _         => "->"
              }

              val pStr = instancePortExpr.get(iSymbol) flatMap {
                _.get(pSymbol.name)
              } map { expr =>
                vexpr(expr, wrapCat = true, indent = 2)
              } getOrElse {
                "/* not connected */"
              }

              val comma = if (i == lastIndex) "" else ","

              items append { List(s".${pSymbol.name}", s"/* ${dir} */ (${pStr})${comma}") }
            }

            items.toList
          }

          body.emit(1)(");")
        }
      }
    }
  }

  private def emitConnects(body: CodeWriter): Unit = {
    if (nonPortConnects.nonEmpty) {
      body.emitSection(1, "Connections") {
        for (EntConnect(lhs, rhs :: Nil) <- nonPortConnects) {
          val assignLhs = vexpr(rhs, wrapCat = true, indent = 1)
          val assignRhs = vexpr(lhs, wrapCat = true, indent = 1)
          body.emit(1)(s"assign ${assignLhs} = ${assignRhs};")
        }
      }
    }
  }

  private def emitVerbatimSection(body: CodeWriter): Unit = {
    val text = entity.verbatims collect {
      case EntVerbatim("verilog", body) => body
    } mkString "\n"

    if (text.nonEmpty) {
      body.emitSection(1, "Verbatim section") {
        body.emit(1)(text)
      }
    }
  }

  private def emitBody(body: CodeWriter): Unit = {
    emitDeclarationSection(body)

    emitInternalStorageSection(body)

    emitCombProcesses(body)

    emitInstances(body)

    emitConnects(body)

    emitVerbatimSection(body)
  }

  def emitPortDeclarations(body: CodeWriter): Unit = {
    val items = new ListBuffer[String]

    if (needsClock) {
      items append "input  wire clk"
    }
    if (needsReset) {
      items append s"input  wire ${cc.rst}"
    }

    for (Decl(symbol, _) <- entity.declarations) {
      symbol.kind match {
        case _: TypeIn => items append s"input  wire ${vdecl(symbol)}"
        case _: TypeOut => {
          val word = if (isVerbatim || (netSymbols contains symbol)) "wire" else "reg "
          items append s"output ${word} ${vdecl(symbol)}"
        }
        case _ => ()
      }
    }

    if (items.nonEmpty) {
      items.init foreach { line =>
        body.emit(1)(line + ",")
      }
      body.emit(1)(items.last)
    }
  }

  def moduleSource: String = {
    val body = new CodeWriter

    body.emit(0)("`default_nettype none")
    body.ensureBlankLine()

    body.emit(0)(s"module ${entity.symbol.name}(")
    emitPortDeclarations(body)
    body.emit(0)(");")
    body.ensureBlankLine()

    emitBody(body)

    body.emit(0)("endmodule")
    body.ensureBlankLine()
    body.emit(0)("`default_nettype wire")

    body.text
  }
}
