////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2019 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Geza Lore
//
// DESCRIPTION:
//
// Compilation test framework
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path

import com.argondesign.alogic.ast.Trees.Entity
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.Error
import com.argondesign.alogic.core.Fatal
import com.argondesign.alogic.core.FatalErrorException
import com.argondesign.alogic.core.InternalCompilerErrorException
import com.argondesign.alogic.core.Message
import com.argondesign.alogic.core.Settings
import com.argondesign.alogic.core.Warning
import com.argondesign.alogic.core.enums.ResetStyle
import com.argondesign.alogic.util.unreachable
import org.scalatest.FreeSpecLike
import org.scalatest.ParallelTestExecution

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.sys.process._

trait CompilationTest extends FreeSpecLike with AlogicTest with ParallelTestExecution {

  private val verilatorPath = new File("verilator/install/bin/verilator")

  if (!verilatorPath.exists) {
    cancel("Run the 'setup-verilator' script to install Verilator locally for testing")
  }

  private val yosysPath = new File("yosys/install/bin/yosys")

  if (!yosysPath.exists) {
    cancel("Run the 'setup-yosys' script to install Yosys locally for testing")
  }

  // Will be updated form multiple threads so must be thread safe
  private val outputs = scala.collection.concurrent.TrieMap[String, String]()

  // Insert compiler output to the 'outputs' map above
  private def entityWriterFactory(entity: Entity, suffix: String): Writer = {
    new StringWriter {
      override def close(): Unit = {
        outputs(entity.name + suffix) = this.toString
        super.close()
      }
    }
  }

  // Compiler message buffer
  private val messages = new ListBuffer[Message]

  // Save messages to the buffer above
  private def messageEmitter(msg: Message, cc: CompilerContext) = {
    messages synchronized {
      messages append msg
    }
  }

  // Create temporary directory, run function passing the path to the temporary
  // directory as argument, then remove the temporary directory
  private def withTmpDir[R](f: Path => R): R = {
    val tmpDir = Files.createTempDirectory("alogic-test-")
    try {
      f(tmpDir)
    } finally {
      def del(f: File): Unit = {
        if (f.isDirectory) {
          f.listFiles foreach del
        }
        f.delete()
      }
      del(tmpDir.toFile)
    }
  }

  // Lint compiler output with verilator
  private def verilatorLint(topLevel: String): Unit = withTmpDir { tmpDir =>
    // Write all files to temporary directory
    for ((name, text) <- outputs) {
      val pw = new PrintWriter(tmpDir.resolve(name).toFile)
      pw.write(text)
      pw.flush()
      pw.close()
    }

    // Lint the top level
    val ret = s"${verilatorPath} --lint-only -Wall -y ${tmpDir} ${topLevel}".!

    // Fail test if lint failed
    if (ret != 0) {
      fail("Verilator lint error")
    }
  }

  private def yosysFEC(topLevel: String, golden: String): Unit = withTmpDir { tmpDir =>
    // Write all files to temporary directory
    for ((name, text) <- outputs) {
      val pw = new PrintWriter(tmpDir.resolve(name).toFile)
      pw.write(text)
      pw.flush()
      pw.close()
    }

    // Write the golden model
    val goldenPath = tmpDir.resolve("__golden.v").toFile
    val gpw = new PrintWriter(goldenPath)
    gpw.write(golden)
    gpw.flush()
    gpw.close()

    // Write yosys script
    val scriptPath = tmpDir.resolve("fec.ys").toFile
    val spw = new PrintWriter(scriptPath)
    spw.write(
      s"""|read_verilog ${goldenPath}
          |prep -flatten -top ${topLevel}
          |opt -full ${topLevel}
          |design -stash gold
          |
          |read_verilog ${tmpDir.resolve(topLevel + ".v").toFile}
          |hierarchy -check -libdir ${tmpDir} -top ${topLevel}
          |prep -flatten -run coarse: -top ${topLevel}
          |opt -full ${topLevel}
          |design -stash comp
          |
          |design -copy-from gold -as gold ${topLevel}
          |design -copy-from comp -as comp ${topLevel}
          |
          |equiv_make gold comp equiv
          |prep -flatten -top equiv
          |opt -full equiv
          |opt_clean -purge
          |
          |#show -prefix equiv-prep -colors 1 -stretch
          |
          |equiv_simple -seq 5
          |equiv_induct -seq 50
          |
          |equiv_status -assert
          |""".stripMargin
    )
    spw.flush()
    spw.close()

    // Log path
    val logPath = tmpDir.resolve("fec.log")

    // Perform the equivalence check
    val ret = s"${yosysPath} -s ${scriptPath} -q -l ${logPath}".!

    // Fail test if fec failed
    if (ret != 0) {
      //s"cat ${logPath}".!
      fail("Yosys FEC failed")
    }
  }

  private sealed trait MessageSpec {
    val file: String
    val line: Int
    val patterns: List[String]

    def matches(message: Message)(implicit cc: CompilerContext): Boolean = {
      val typeMatches = (this, message) match {
        case (_: WarningSpec, _: Warning) => true
        case (_: ErrorSpec, _: Error)     => true
        case (_: FatalSpec, _: Fatal)     => true
        case _                            => false
      }
      typeMatches && // Right type
      message.loc.line == line && // Right line number
      file.r.pattern.matcher(message.loc.file).matches() && // Right file pattern
      message.msg.lengthIs == patterns.length && { // Right length
        ((patterns map { _.r }) zip message.msg) forall { pair => // Right text
          pair._1.pattern.matcher(pair._2).matches()
        }
      }
    }

    def string: String = {
      val kindString = this match {
        case _: WarningSpec => "WARNING"
        case _: ErrorSpec   => "ERROR"
        case _: FatalSpec   => "FATAL"
      }
      val prefix = s"${file}:${line}: ${kindString}: "
      val triplets = LazyList.continually(prefix) lazyZip ("" +: LazyList.continually("... ")) lazyZip patterns
      triplets map { case (a, b, c) => a + b + c } mkString "\n"
    }
  }

  private case class WarningSpec(file: String, line: Int, patterns: List[String])
      extends MessageSpec
  private case class ErrorSpec(file: String, line: Int, patterns: List[String]) extends MessageSpec
  private case class FatalSpec(file: String, line: Int, patterns: List[String]) extends MessageSpec

  private def parseCheckFile(checkFile: String): (Map[String, String], List[MessageSpec]) = {
    val attr = mutable.Map[String, String]()

    val pairMatcher = """@(.+):(.*)""".r
    val longMatcher = """@(.+)\{\{\{""".r
    val boolMatcher = """@(.+)""".r
    val mesgMatcher = """(.*):(\d+): (WARNING|ERROR|FATAL): (\.\.\.)?(.*)""".r

    val mesgSpecs = new ListBuffer[MessageSpec]

    val mesgBuff = new ListBuffer[String]
    var mesgType = ""
    var mesgFile = ""
    var mesgLine = ""

    def finishMeasageSpec() = {
      val file = if (mesgFile.isEmpty) ".*" + checkFile.split("/").last else mesgFile
      mesgType match {
        case "WARNING" => mesgSpecs append WarningSpec(file, mesgLine.toInt, mesgBuff.toList)
        case "ERROR"   => mesgSpecs append ErrorSpec(file, mesgLine.toInt, mesgBuff.toList)
        case "FATAL"   => mesgSpecs append FatalSpec(file, mesgLine.toInt, mesgBuff.toList)
        case _         => unreachable
      }
      mesgBuff.clear()
    }

    val longBuff = new ListBuffer[String]
    var longAttr = ""

    def finishLongAttr() = {
      attr(longAttr) = longBuff mkString "\n"
      longBuff.clear()
      longAttr = ""
    }

    for {
      line <- Source.fromFile(checkFile).getLines map { _.trim }
      if line startsWith "//"
    } {
      val text = line.drop(2)
      text.trim match {
        case pairMatcher(k, v) if longAttr == "" => attr(k.trim) = v.trim
        case longMatcher(k) if longAttr == ""    => longAttr = k.trim
        case "}}}" if longAttr != ""             => finishLongAttr()
        case _ if longAttr != ""                 => longBuff append text
        case boolMatcher(k) if longAttr == ""    => attr(k.trim) = ""
        case mesgMatcher(file, line, kind, null, pattern) if longAttr == "" =>
          if (mesgBuff.nonEmpty) {
            finishMeasageSpec()
          }
          mesgFile = file.trim
          mesgLine = line.trim
          mesgType = kind.trim
          mesgBuff append pattern.trim
        case mesgMatcher(file, line, kind, _, pattern) if longAttr == "" =>
          assert(mesgFile == file.trim, "Message continuation must have same file pattern")
          assert(mesgLine == line.trim, "Message continuation must have same line number")
          assert(mesgType == kind.trim, "Message continuation must have same message type")
          mesgBuff append pattern.trim
        case _ =>
      }
    }

    if (mesgBuff.nonEmpty) {
      finishMeasageSpec()
    }

    (attr.toMap, mesgSpecs.toList)
  }

  def defineTest(name: String, searchPath: File, top: String, checkFile: String): Unit = {
    name in {
      // Create compiler context
      implicit val cc: CompilerContext = new CompilerContext(
        Settings(
          moduleSearchDirs = List(searchPath),
          entityWriterFactory = entityWriterFactory,
          messageEmitter = messageEmitter,
          dumpTrees = false,
          resetStyle = ResetStyle.SyncHigh
        )
      )

      // Do the compilation
      try {
        cc.compile(List(top))
      } catch {
        case _: FatalErrorException =>
        case e: InternalCompilerErrorException =>
          print(e.message.string)
          throw e
      }

      // Parse the check file
      val (attr, messageSpecs) = parseCheckFile(checkFile)

      // Check messages
      {
        // fail flag
        var messageCheckFailed = false

        // Group messages by specs
        val messageGroups = messages.toList groupBy { message =>
          messageSpecs find { _ matches message }
        }

        // Fail if a spec matches multiple messages
        for ((Some(spec), messages) <- messageGroups if messages.lengthIs > 1) {
          println("Message pattern:")
          println(spec.string)
          println("Matches multiple messages:")
          messages foreach { message =>
            println(message.string)
          }
          messageCheckFailed = true
        }

        // Print unexpected messages
        messageGroups.get(None) foreach { unexpectedMessages =>
          unexpectedMessages foreach { message =>
            println("Unexpected message:")
            println(message.string)
          }
          messageCheckFailed = true
        }

        // Print unused patterns
        for {
          spec <- messageSpecs
          if !(messageGroups contains Some(spec))
        } {
          println("Unused message pattern:")
          println(spec.string)
          messageCheckFailed = true
        }

        // Fail test if message check failed
        if (messageCheckFailed) {
          fail("Message check failed")
        }
      }

      // If an ERROR or a FATAL is expected, the compilation should have failed
      val expectedToFail = messageSpecs exists {
        case _: ErrorSpec => true
        case _: FatalSpec => true
        case _            => false
      }

      // Check compilation status
      cc.hasError shouldBe expectedToFail

      // Lint (if it's supposed to succeed and not told otherwise by the test
      if (!expectedToFail && !(attr contains "verilator-lint-off")) {
        verilatorLint(attr.getOrElse("out-top", top))
      }

      // Perform equivalence check with golden reference if provided
      if (!expectedToFail && (attr contains "fec-golden")) {
        yosysFEC(attr.getOrElse("out-top", top), attr("fec-golden"))
      }
    }
  }
}
