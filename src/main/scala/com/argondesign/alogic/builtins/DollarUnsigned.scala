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
// Builtin '$unsigned'
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.builtins

import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.Loc
import com.argondesign.alogic.core.Types._

private[builtins] class DollarUnigned(implicit cc: CompilerContext) extends BuiltinPolyFunc {

  val name = "$unsigned"

  def returnType(args: List[Expr]) = args partialMatch {
    case List(arg) if arg.tpe.isPacked => TypeUInt(ExprNum(false, arg.tpe.width) regularize arg.loc)
    case List(arg) if arg.tpe.isNum    => TypeNum(false)
  }

  def combArgs(args: List[Expr]) = List(args(0))

  def fold(loc: Loc, args: List[Expr]) = args partialMatch {
    case List(ExprNum(true, v)) => ExprNum(false, v)
  }
}
