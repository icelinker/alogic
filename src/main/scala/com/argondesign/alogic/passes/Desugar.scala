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
// Desugar rewrites basic syntactic sugar elements and also
// brings the tree to a canonical form by removing nested block where
// they are redundant/disallowed.
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.passes

import com.argondesign.alogic.ast.TreeTransformer
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.util.unreachable

final class Desugar(implicit cc: CompilerContext) extends TreeTransformer {

  override def transform(tree: Tree): Tree = tree match {
    // "a++" rewritten as  "a = a + <width of a>'d1"
    case StmtPost(lhs, op) => {
      val one = ExprInt(false, lhs.tpe.width, 1) regularize tree.loc
      val rhs = op match {
        case "++" => lhs + one
        case "--" => lhs - one
        case _    => unreachable
      }
      StmtAssign(lhs, rhs) regularize tree.loc
    }

    // "a += b" rewritten as "a = a + b"
    case StmtUpdate(lhs, op, expr) => {
      StmtAssign(lhs, ExprBinary(lhs, op, expr)) regularize tree.loc
    }

    // "let(<init>) <stmts>" rewritten as "<init> <stmts>"
    case StmtLet(inits, stmts) => {
      Thicket(inits ::: stmts) regularize tree.loc
    }

    case _ => tree
  }

  override def finalCheck(tree: Tree): Unit = {
    // Should have removed all StmtLet, StmtUpdate, StmtPost
    tree visit {
      case node: StmtLet    => cc.ice(node, s"StmtLet remains")
      case node: StmtUpdate => cc.ice(node, s"StmtUpdate remains")
      case node: StmtPost   => cc.ice(node, s"StmtPost remains")
    }
  }

}

object Desugar extends TreeTransformerPass {
  val name = "desugar"
  def create(implicit cc: CompilerContext) = new Desugar
}
