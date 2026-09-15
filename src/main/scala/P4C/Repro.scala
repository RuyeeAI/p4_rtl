package P4C

import java.nio.file.{Files, Paths}

object Repro {
  def main(args: Array[String]): Unit = {
    val dir = Files.createTempDirectory("p4c-repro")
    val f = dir.resolve("t.p4")
    Files.write(f, Files.readAllBytes(Paths.get("/tmp/p4c-qa/t.p4")))
    val irDir = Files.createTempDirectory("p4c-repro-ir")
    val r = Generate.compileFile(f, dir, None, 4, irDir = Some(irDir))
    import scala.jdk.CollectionConverters._
    Files.list(irDir).iterator().asScala.foreach { p =>
      println(s"===== ${p.getFileName} =====")
      println(new String(Files.readAllBytes(p)))
    }
  }
}
