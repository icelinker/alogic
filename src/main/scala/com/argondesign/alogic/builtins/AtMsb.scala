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
// Builtin '@msb'
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.builtins

import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.Loc
import com.argondesign.alogic.core.Types._

private[builtins] class AtMsb(implicit cc: CompilerContext) extends BuiltinPolyFunc {

  val name = "@msb"

  def returnType(args: List[Expr]) = args partialMatch {
    case List(expr) if expr.tpe.isPacked && expr.tpe.width > 0 =>
      TypeInt(expr.tpe.isSigned, Expr(1) withLoc expr.loc)
  }

  def combArgs(args: List[Expr]) = List(args(0))

  def fold(loc: Loc, args: List[Expr]) = AtMsb.fold(loc, args(0))

}

private[builtins] object AtMsb {
  def fold(loc: Loc, expr: Expr)(implicit cc: CompilerContext): Option[Expr] =
    if (expr.tpe.width == 1) {
      Some(expr)
    } else {
      Some((expr index (expr.tpe.width - 1)).simplify)
    }
}
