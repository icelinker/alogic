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
// Factory to build output register entities
////////////////////////////////////////////////////////////////////////////////
package com.argondesign.alogic.core

import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.FlowControlTypes.FlowControlTypeNone
import com.argondesign.alogic.core.StorageTypes._
import com.argondesign.alogic.core.Types._
import com.argondesign.alogic.typer.TypeAssigner

object SyncRegFactory {

  /*

  // Register slice interface

  // Hardware interface:
  _ip
  _ip_valid

  _op
  _op_valid

  at beginning:
  _ip_valid = 1'b0

   */

  // Build an entity similar to the following Alogic FSM to be used as an
  // output register implementation. The body of the main function is filled
  // in by the above implementations.
  //
  // fsm sync_reg {
  //   // Upstream interface
  //   in payload_t ip;
  //   in bool ip_valid;
  //
  //   // Downstream interface
  //   out wire payload_t op;
  //   out wire bool op_valid;
  //
  //   // Local storage
  //   payload_t payload;
  //   bool valid = false;
  //
  //   void main() {
  //     if (ip_valid) {
  //       payload = ip;
  //     }
  //     valid = ip_valid;
  //   }
  //
  //   payload -> op;
  //   valid -> op_valid;
  // }
  private def buildSyncReg(
      name: String,
      loc: Loc,
      kind: Type,
      sep: String
  )(
      implicit cc: CompilerContext
  ): Entity = {
    val fcn = FlowControlTypeNone
    val stw = StorageTypeWire

    val bool = TypeUInt(TypeAssigner(Expr(1) withLoc loc))

    lazy val ipSymbol = cc.newTermSymbol("ip", loc, TypeIn(kind, fcn))
    val ipvSymbol = cc.newTermSymbol(s"ip${sep}valid", loc, TypeIn(bool, fcn))

    lazy val opSymbol = cc.newTermSymbol("op", loc, TypeOut(kind, fcn, stw))
    val opvSymbol = cc.newTermSymbol(s"op${sep}valid", loc, TypeOut(bool, fcn, stw))

    lazy val pSymbol = cc.newTermSymbol("payload", loc, kind)
    val vSymbol = cc.newTermSymbol("valid", loc, bool)

    lazy val ipRef = ExprSym(ipSymbol)
    val ipvRef = ExprSym(ipvSymbol)

    lazy val opRef = ExprSym(opSymbol)
    val opvRef = ExprSym(opvSymbol)

    lazy val pRef = ExprSym(pSymbol)
    val vRef = ExprSym(vSymbol)

    val statements = if (kind != TypeVoid) {
      List(
        StmtIf(
          ipvRef,
          List(StmtAssign(pRef, ipRef)),
          Nil
        ),
        StmtAssign(vRef, ipvRef)
      )
    } else {
      List(StmtAssign(vRef, ipvRef))
    }

    val ports = if (kind != TypeVoid) {
      List(ipSymbol, ipvSymbol, opSymbol, opvSymbol)
    } else {
      List(ipvSymbol, opvSymbol)
    }

    val symbols = if (kind != TypeVoid) pSymbol :: vSymbol :: ports else vSymbol :: ports

    val decls = symbols map {
      case `vSymbol` => Decl(vSymbol, Some(ExprInt(false, 1, 0)))
      case symbol    => Decl(symbol, None)
    } map {
      EntDecl(_)
    }

    val connects = if (kind != TypeVoid) {
      List(
        EntConnect(pRef, List(opRef)),
        EntConnect(vRef, List(opvRef))
      )
    } else {
      List(
        EntConnect(vRef, List(opvRef))
      )
    }

    val eKind = TypeEntity(name, ports, Nil)
    val entitySymbol = cc.newTypeSymbol(name, loc, eKind)
    entitySymbol.attr.variant set "fsm"
    entitySymbol.attr.highLevelKind set eKind
    val entity = Entity(Sym(entitySymbol, Nil), decls ::: EntCombProcess(statements) :: connects)
    entity regularize loc
  }

  def apply(
      name: String,
      loc: Loc,
      kind: Type
  )(
      implicit cc: CompilerContext
  ): Entity = {
    require(kind.isPacked)
    buildSyncReg(name, loc, kind, cc.sep)
  }

}
