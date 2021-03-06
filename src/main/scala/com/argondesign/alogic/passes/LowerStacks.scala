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
// - Lower stack variables into stack instances
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.passes

import com.argondesign.alogic.ast.TreeTransformer
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.StackFactory
import com.argondesign.alogic.core.Symbols._
import com.argondesign.alogic.core.Types.TypeStack
import com.argondesign.alogic.core.Types._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

final class LowerStacks(implicit cc: CompilerContext) extends TreeTransformer {

  // Map from original stack variable symbol to the
  // corresponding stack entity and instance symbols
  private[this] val stackMap = mutable.Map[TermSymbol, (Entity, TermSymbol)]()

  // Stack of extra statements to emit when finished with a statement
  private[this] val extraStmts = mutable.Stack[mutable.ListBuffer[Stmt]]()

  override def skip(tree: Tree): Boolean = tree match {
    case entity: Entity => entitySymbol.attr.variant.value == "network"
    case _              => false
  }

  override def enter(tree: Tree): Unit = tree match {
    case Decl(symbol, _) =>
      symbol.kind match {
        case TypeStack(kind, depth) =>
          // Construct the stack entity
          val loc = tree.loc
          val pName = symbol.name
          // TODO: mark inline
          val eName = entitySymbol.name + cc.sep + "stack" + cc.sep + pName
          val stackEntity = StackFactory(eName, loc, kind, depth)
          val instanceSymbol = cc.newTermSymbol(pName, loc, TypeInstance(stackEntity.symbol))
          stackMap(symbol) = (stackEntity, instanceSymbol)
          // Clear enable when the entity stalls
          entitySymbol.attr.interconnectClearOnStall.append((instanceSymbol, "en"))
        case _ =>
      }

    ////////////////////////////////////////////////////////////////////////////
    // FlowControlTypeReady
    ////////////////////////////////////////////////////////////////////////////

    case _: Stmt => {
      // Whenever we enter a new statement, add a new buffer to
      // store potential extra statements
      extraStmts.push(ListBuffer())
    }

    case _ =>
  }

  private[this] def assignTrue(expr: Expr) = StmtAssign(expr, ExprInt(false, 1, 1))
  private[this] def assignFalse(expr: Expr) = StmtAssign(expr, ExprInt(false, 1, 0))

  override def transform(tree: Tree): Tree = {
    val result: Tree = tree match {

      //////////////////////////////////////////////////////////////////////////
      // Rewrite statements
      //////////////////////////////////////////////////////////////////////////

      case StmtExpr(ExprCall(ExprSelect(ExprSym(symbol: TermSymbol), "push", _), args)) => {
        stackMap.get(symbol) map {
          case (_, iSymbol) => {
            StmtBlock(
              List(
                assignTrue(ExprSym(iSymbol) select "en"),
                assignTrue(ExprSym(iSymbol) select "push"),
                StmtAssign(ExprSym(iSymbol) select "d", args.head)
              ))
          }
        } getOrElse {
          tree
        }
      }

      case StmtExpr(ExprCall(ExprSelect(ExprSym(symbol: TermSymbol), "set", _), args)) => {
        stackMap.get(symbol) map {
          case (_, iSymbol) => {
            StmtBlock(
              List(
                assignTrue(ExprSym(iSymbol) select "en"),
                StmtAssign(ExprSym(iSymbol) select "d", args.head)
              ))
          }
        } getOrElse {
          tree
        }
      }

      //////////////////////////////////////////////////////////////////////////
      // Rewrite expressions
      //////////////////////////////////////////////////////////////////////////

      case ExprCall(ExprSelect(ExprSym(symbol: TermSymbol), "pop", _), Nil) => {
        stackMap.get(symbol) map {
          case (_, iSymbol) => {
            extraStmts.top append assignTrue(ExprSym(iSymbol) select "en")
            extraStmts.top append assignTrue(ExprSym(iSymbol) select "pop")
            ExprSym(iSymbol) select "q"
          }
        } getOrElse {
          tree
        }
      }

      case ExprSelect(ExprSym(symbol: TermSymbol), "top", _) => {
        stackMap.get(symbol) map {
          case (_, iSymbol) => ExprSym(iSymbol) select "q"
        } getOrElse {
          tree
        }
      }

      case ExprSelect(ExprSym(symbol: TermSymbol), "full", _) => {
        stackMap.get(symbol) map {
          case (_, iSymbol) => ExprSym(iSymbol) select "full"
        } getOrElse {
          tree
        }
      }

      case ExprSelect(ExprSym(symbol: TermSymbol), "empty", _) => {
        stackMap.get(symbol) map {
          case (_, iSymbol) => ExprSym(iSymbol) select "empty"
        } getOrElse {
          tree
        }
      }

      //////////////////////////////////////////////////////////////////////////
      // Add stack entities
      //////////////////////////////////////////////////////////////////////////

      case entity: Entity if stackMap.nonEmpty => {
        val newBody = List from {
          // Drop stack declarations and the comb process
          entity.body.iterator filterNot {
            case EntDecl(Decl(symbol, _)) => symbol.kind.isStack
            case _: EntCombProcess        => true
            case _                        => false
          } concat {
            // Add instances
            for ((entity, instance) <- stackMap.values) yield {
              EntInstance(Sym(instance, Nil), Sym(entity.symbol, Nil), Nil, Nil)
            }
          } concat {
            Iterator single {
              // Add leading statements to the state system
              assert(entity.combProcesses.lengthIs <= 1)

              val leading = stackMap.values map {
                _._2
              } map { iSymbol =>
                val iRef = ExprSym(iSymbol)
                StmtBlock(
                  List(
                    assignFalse(iRef select "en"),
                    StmtAssign(iRef select "d", iRef select "q"), // TODO: redundant
                    assignFalse(iRef select "push"), // TODO: redundant
                    assignFalse(iRef select "pop") // TODO: redundant
                  )
                )
              }

              entity.combProcesses.headOption map {
                case EntCombProcess(stmts) => EntCombProcess(List.concat(leading, stmts))
              } getOrElse {
                EntCombProcess(leading.toList)
              }
            }
          }
        }

        val newEntity = entity.copy(body = newBody)

        val stackEntities = stackMap.values map { _._1 }

        Thicket(newEntity :: stackEntities.toList)
      }

      case _ => tree
    }

    // Emit any extra statement with this statement
    val result2 = result match {
      case stmt: Stmt =>
        val extra = extraStmts.pop()
        if (extra.isEmpty) stmt else StmtBlock((extra append stmt).toList)
      case _ => result
    }

    // If we did modify the node, regularize it
    if (result2 ne tree) {
      result2 regularize tree.loc
    }

    // Done
    result2
  }

  override def finalCheck(tree: Tree): Unit = {
    assert(extraStmts.isEmpty)

    tree visit {
      case node @ ExprCall(ExprSelect(ref, sel, _), _) if ref.tpe.isInstanceOf[TypeStack] => {
        cc.ice(node, s"Stack .${sel} remains")
      }
    }
  }

}

object LowerStacks extends TreeTransformerPass {
  val name = "lower-stacks"
  def create(implicit cc: CompilerContext) = new LowerStacks
}
