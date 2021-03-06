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
// Any symbol driven combinatorially through the FSM logic must be assigned a
// value on all code paths to avoid latches. In this phase we add all such
// default assignments.
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.passes

import com.argondesign.alogic.analysis.Liveness
import com.argondesign.alogic.ast.TreeTransformer
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.Symbols._
import com.argondesign.alogic.typer.TypeAssigner

import scala.collection.mutable

final class DefaultAssignments(implicit cc: CompilerContext) extends TreeTransformer {

  private val needsDefault = mutable.Set[TermSymbol]()

  override def skip(tree: Tree): Boolean = tree match {
    case entity: Entity => entity.combProcesses.isEmpty
    case _              => false
  }

  override def enter(tree: Tree): Unit = tree match {
    case Decl(symbol, _) if symbol.kind.isIn || symbol.kind.isConst => ()

    case Decl(symbol, _) if !symbol.attr.flop.isSet && !symbol.attr.memory.isSet => {
      needsDefault += symbol
    }

    case _ => ()
  }

  override def transform(tree: Tree): Tree = tree match {
    case entity: Entity if needsDefault.nonEmpty => {
      // Remove any nets driven through a connect
      for (EntConnect(_, List(rhs)) <- entity.connects) {
        rhs.visit {
          case ExprSym(symbol: TermSymbol) => {
            needsDefault remove symbol
          }
        }
      }

      // Remove symbols that are dead at the beginning of the cycle. To do
      // this, we build the case statement representing the state dispatch
      // (together with the fence statements), and do liveness analysis on it
      lazy val (liveSymbolBits, deadSymbolBits) = Liveness(entity.combProcesses(0).stmts)

      if (needsDefault.nonEmpty) {
        assert(entity.combProcesses.lengthIs == 1)

        val deadSymbols = {
          // Keep only the symbols with all bits dead
          val it = deadSymbolBits collect {
            case (symbol, set) if set.size == symbol.kind.width => symbol
          }
          it.toSet
        }

        // Now retain only the symbols that are not dead
        needsDefault filterInPlace { symbol =>
          !(deadSymbols contains symbol)
        }
      }

      if (needsDefault.isEmpty) {
        tree
      } else {
        // Symbols have default assignments set as follows:
        // If a symbol is live or drives a connection, initialize to its default value
        // otherwise zero.
        val initializeToRegisteredVal = {
          val liveSymbols = liveSymbolBits.underlying.keySet

          val symbolsDrivingConnect = Set from {
            entity.connects.iterator flatMap {
              case EntConnect(lhs, _) =>
                lhs.collect {
                  case ExprSym(symbol: TermSymbol) => symbol.attr.flop.getOrElse(symbol)
                }
            }
          }

          liveSymbols union symbolsDrivingConnect
        }

        val leading = for {
          Decl(symbol, _) <- entity.declarations
          if needsDefault contains symbol
        } yield {
          val init = if ((initializeToRegisteredVal contains symbol) && symbol.attr.default.isSet) {
            symbol.attr.default.value
          } else {
            val kind = symbol.kind
            ExprInt(kind.isSigned, kind.width, 0)
          }
          StmtAssign(ExprSym(symbol), init) regularize symbol.loc
        }

        val newBody = entity.body map {
          case ent @ EntCombProcess(stmts) =>
            TypeAssigner(EntCombProcess(leading ::: stmts) withLoc ent.loc)
          case other => other
        }

        TypeAssigner {
          entity.copy(body = newBody) withLoc tree.loc
        }
      }
    }

    case _ => tree
  }

}

object DefaultAssignments extends TreeTransformerPass {
  val name = "default-assignments"
  def create(implicit cc: CompilerContext) = new DefaultAssignments
}
