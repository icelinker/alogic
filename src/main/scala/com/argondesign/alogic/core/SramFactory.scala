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
// Factory to build sram entities
////////////////////////////////////////////////////////////////////////////////
package com.argondesign.alogic.core

import com.argondesign.alogic.ast.Trees.StmtAssign
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.FlowControlTypes.FlowControlTypeNone
import com.argondesign.alogic.core.StorageTypes._
import com.argondesign.alogic.core.Types._
import com.argondesign.alogic.lib.Math
import com.argondesign.alogic.typer.TypeAssigner

object SramFactory {

  /*
    fsm sram {
      in bool ce;
      in bool we;
      in uint($clog2(DEPTH)) addr;
      in uint(WIDTH) wdata;
      out uint(WIDTH) rdata;

      uint(WIDTH) storage[DEPTH];

      void main() {
        if (ce) {
          if (we) {
            stroage.write(addr, wdata);
            rdata = 0; // Or anything really
          } else {
            rdata = storage[addr];
          }
        }
        fence;
      }
    }

   */

  def apply(
      name: String,
      loc: Loc,
      width: Int,
      depth: Int
  )(
      implicit cc: CompilerContext
  ): Entity = {

    val fcn = FlowControlTypeNone

    val bool = TypeUInt(TypeAssigner(Expr(1) withLoc loc))

    val addrKind = TypeUInt(Expr(Math.clog2(depth)) regularize loc)
    val dataKind = TypeUInt(Expr(width) regularize loc)
    val storKind = TypeArray(dataKind, Expr(depth) regularize loc)

    val ceSymbol = cc.newTermSymbol("ce", loc, TypeIn(bool, fcn))
    val weSymbol = cc.newTermSymbol("we", loc, TypeIn(bool, fcn))
    val adSymbol = cc.newTermSymbol("addr", loc, TypeIn(addrKind, fcn))
    val wdSymbol = cc.newTermSymbol("wdata", loc, TypeIn(dataKind, fcn))
    val rdSymbol = cc.newTermSymbol("rdata", loc, TypeOut(dataKind, fcn, StorageTypeReg))
    val stSymbol = cc.newTermSymbol("storage", loc, storKind)

    val ceRef = ExprSym(ceSymbol)
    val weRef = ExprSym(weSymbol)
    val adRef = ExprSym(adSymbol)
    val wdRef = ExprSym(wdSymbol)
    val rdRef = ExprSym(rdSymbol)
    val stRef = ExprSym(stSymbol)

    val statements = List(
      StmtIf(
        ceRef,
        List(
          StmtIf(
            weRef,
            List(
              StmtExpr(ExprCall(stRef select "write", List(adRef, wdRef))),
              StmtAssign(rdRef, ExprInt(false, width, 0))
            ),
            List(
              StmtAssign(rdRef, stRef index adRef)
            )
          )
        ),
        Nil
      )
    )

    val ports = List(ceSymbol, weSymbol, adSymbol, wdSymbol, rdSymbol)

    val symbols = stSymbol :: ports

    val decls = symbols map { symbol =>
      EntDecl(Decl(symbol, None))
    }

    val eKind = TypeEntity(name, ports, Nil)
    val entitySymbol = cc.newTypeSymbol(name, loc, eKind)
    entitySymbol.attr.variant set "fsm"
    entitySymbol.attr.sram set true
    entitySymbol.attr.highLevelKind set eKind
    val entity = Entity(Sym(entitySymbol, Nil), decls ::: EntCombProcess(statements) :: Nil)
    entity regularize loc
  }

}
