package P4C

import P4C.Ast.P4Program

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** 编译驱动：.p4 文件 → 生成的 Chisel（Scala）源码。 */
object Generate {

  final case class Result(p4File: Path, scalaFile: Path, modules: Seq[String])

  /** 拍数预算校验（D1：全局参数，1 = 不切拍）。 */
  private def checkStages(stages: Int): Unit =
    if (stages < 1) throw new P4Error(s"拍数预算 N 必须 ≥ 1（got $stages）")

  /** E2：声明级切拍指示生效日志（control 覆盖生效 / parser 仅记录）。 */
  private def directiveLogs(prog: P4Program, name: String): Seq[String] =
    prog.controls.collect {
      case c if c.stagesOpt.isDefined =>
        s"[P4C] $name: control ${c.name} stages=${c.stagesOpt.get} (directive)"
    } ++ prog.parsers.collect {
      case p if p.stagesOpt.isDefined =>
        s"[P4C] $name: parser ${p.name} stages=${p.stagesOpt.get} (directive，parser 当前不切拍，仅记录)"
    } ++ prog.controls.flatMap(_.tables.filter(_.isRuntime).map { t =>
      s"[P4C] $name: table ${t.name} runtime size=${t.runtimeSize} (directive)"
    })

  /** Pascal 化文件主干名：demo6-deepchain -> Demo6Deepchain */
  private def pascalStem(fileName: String): String = {
    val stem = fileName.stripSuffix(".p4")
    stem.split("-|_").map(s => if (s.isEmpty) s else s.charAt(0).toUpper + s.substring(1)).mkString
  }

  /** 单文件编译：返回生成的 .scala 路径（同时可选拷贝一份到发布目录）。
    * @param stages 拍数预算（1 = 不切拍，默认模式与历史版本逐字节等价）
    * @param sigDir 签名/调度 JSON 输出目录（None = 不导出）
    * @param clock 时钟约束（每级最大权重上限；X2 clock 模式，与 stages 互斥）
    * @param irDir XLS 风格 IR 文本输出目录（None = 不导出；每 DAG 一个 .ir 文件） */
  def compileFile(p4Path: Path, outDir: Path, copyDir: Option[Path], stages: Int = 1,
      sigDir: Option[Path] = None, clock: Option[Int] = None,
      model: DelayModel = DelayModels.default, irDir: Option[Path] = None): Result = {
    checkStages(stages)
    val src = new String(Files.readAllBytes(p4Path), StandardCharsets.UTF_8)
    val (prog, warnings) = Parser.parseProgramWithDiagnostics(src)
    // E2：未紧邻被忽略的指示告警（CLI 模式无 log 通道，打 stdout）
    warnings.foreach(println)
    val prefix = pascalStem(p4Path.getFileName.toString)
    val modules =
      prog.controls.map(_.name) ++ prog.parsers.map(_.name + "Parser")
    val sigBuf = if (sigDir.isDefined) Some(scala.collection.mutable.ArrayBuffer.empty[Signature.ControlSig]) else None
    val pkg = pascalStem(p4Path.getFileName.toString)
    val irDump: Option[(String, Ir.Dag) => Unit] = irDir.map { dir =>
      Files.createDirectories(dir)
      (ctx: String, d: Ir.Dag) => {
        val file = dir.resolve(pkg + "__" + IrText.fnNameOf(ctx) + ".ir")
        Files.write(file, IrText.dump(pkg, IrText.fnNameOf(ctx), d).getBytes(StandardCharsets.UTF_8))
      }
    }
    val code = ChiselBackend.emitProgram(prog, prefix, p4Path.getFileName.toString, stages, sigBuf, clock, model, irDump)
    directiveLogs(prog, p4Path.getFileName.toString).foreach(println)

    Files.createDirectories(outDir)
    val out = outDir.resolve(s"$prefix.scala")
    Files.write(out, code.getBytes(StandardCharsets.UTF_8))
    copyDir.foreach { dir =>
      Files.createDirectories(dir)
      Files.write(dir.resolve(out.getFileName.toString), code.getBytes(StandardCharsets.UTF_8))
    }
    sigDir.foreach { dir =>
      Files.createDirectories(dir)
      Files.write(dir.resolve(s"$prefix.json"),
        Signature.toJson(p4Path.getFileName.toString, sigBuf.fold(Seq.empty[Signature.ControlSig])(_.toSeq))
          .getBytes(StandardCharsets.UTF_8))
    }
    Result(p4Path, out, modules)
  }

  /** 批量编译（sbt sourceGenerators 入口）。每个生成文件自带带前缀的 Bundle。返回生成的托管源文件列表。 */
  def generateAll(files: Seq[java.io.File], outDir: java.io.File, copyDir: Option[java.io.File], stages: Int = 1, log: String => Unit,
      sigDir: Option[java.io.File] = None, clock: Option[Int] = None,
      model: DelayModel = DelayModels.default): Seq[java.io.File] = {
    checkStages(stages)
    val outs = scala.collection.mutable.ArrayBuffer.empty[java.io.File]
    Files.createDirectories(outDir.toPath)
    files.sortBy(_.getName).foreach { f =>
      try {
        val src = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
        val (prog, warnings) = Parser.parseProgramWithDiagnostics(src)
        warnings.foreach(log)
        val prefix = pascalStem(f.getName)
        val out = outDir.toPath.resolve(s"$prefix.scala")
        val sigBuf = if (sigDir.isDefined) Some(scala.collection.mutable.ArrayBuffer.empty[Signature.ControlSig]) else None
        val (code, stageCounts) = ChiselBackend.emitModules(prog, prefix, f.getName, stages, sigBuf, clock, model)
        Files.write(out, code.getBytes(StandardCharsets.UTF_8))
        copyDir.foreach { d =>
          Files.createDirectories(d.toPath)
          Files.write(d.toPath.resolve(out.getFileName.toString), Files.readAllBytes(out))
        }
        sigDir.foreach { d =>
          Files.createDirectories(d.toPath)
          Files.write(d.toPath.resolve(s"$prefix.json"),
            Signature.toJson(f.getName, sigBuf.fold(Seq.empty[Signature.ControlSig])(_.toSeq))
              .getBytes(StandardCharsets.UTF_8))
        }
        val modules = prog.controls.map(_.name) ++ prog.parsers.map(_.name + "Parser")
        // 日志：stages==1 时与历史版本逐字一致（D4 门禁）；切拍时附加各 control 实际级数
        val stageInfo = if (stages > 1)
          s", stages: ${prog.controls.map(c => s"${c.name}=${stageCounts.getOrElse(c.name, 1)}").mkString(", ")}"
        else ""
        directiveLogs(prog, f.getName).foreach(log)
        log(s"[P4C] ${f.getName} -> ${out.getFileName} (modules: ${modules.mkString(", ")}$stageInfo)")
        outs += out.toFile
      } catch {
        case e: P4Error => log(s"[P4C] 编译失败 ${f.getName}: ${e.getMessage}")
      }
    }
    outs.toSeq
  }

  /** 切拍变体批量编译（供 p4/demos/staged/ 目录的第二个 sourceGenerator 使用）。
    *
    * 每文件以 prefix + "Staged" 为模块名前缀、按 budget=stages 发射单个变体
    * （不发射 N=1 副本——N=1 基线由主 demos 目录的正常管线提供，避免 p4cgen 包内类名冲突）。
    * 等价性测试配对示例：Demo6DeepchainIngress（N=1）vs Demo6DeepchainStagedIngress（N=4）。
    */
  def generateStagedVariants(
    files: Seq[java.io.File], outDir: java.io.File, stages: Int, log: String => Unit,
    copyDir: Option[java.io.File] = None,
  ): Seq[java.io.File] = {
    checkStages(stages)
    val outs = scala.collection.mutable.ArrayBuffer.empty[java.io.File]
    Files.createDirectories(outDir.toPath)
    files.sortBy(_.getName).foreach { f =>
      try {
        val src = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
        val (prog, warnings) = Parser.parseProgramWithDiagnostics(src)
        warnings.foreach(log)
        val prefix = pascalStem(f.getName) + "Staged"
        val out = outDir.toPath.resolve(s"$prefix.scala")
        val (code, stageCounts) = ChiselBackend.emitModules(prog, prefix, f.getName, stages)
        Files.write(out, code.getBytes(StandardCharsets.UTF_8))
        copyDir.foreach { d =>
          Files.createDirectories(d.toPath)
          Files.write(d.toPath.resolve(out.getFileName.toString), Files.readAllBytes(out))
        }
        val modules = prog.controls.map(_.name) ++ prog.parsers.map(_.name + "Parser")
        val stageInfo = prog.controls.map(c => s"${c.name}=${stageCounts.getOrElse(c.name, 1)}").mkString(", ")
        directiveLogs(prog, f.getName).foreach(log)
        log(s"[P4C] ${f.getName} -> ${out.getFileName} (modules: ${modules.mkString(", ")}, stages: $stageInfo)")
        outs += out.toFile
      } catch {
        case e: P4Error => log(s"[P4C] 编译失败 ${f.getName}: ${e.getMessage}")
      }
    }
    outs.toSeq
  }
}

/** 命令行入口：`P4cMain <in.p4> <outDir> [copyDir] [opts]`（等价 `p4chisel gen`）。
  *
  * 仅为向后兼容保留；实现已统一到 [[P4cCli]].runGen，避免两份参数解析漂移。
  */
object P4cMain {
  def main(args: Array[String]): Unit = System.exit(P4cCli.runGen(args))
}
