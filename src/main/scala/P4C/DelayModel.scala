package P4C

/** 延迟模型（对标 XLS `--delay_model`）：为调度器提供节点的逻辑延迟代价。
  *
  * 口径：以 **ND2（二输入 NAND）的门延迟为归一化单位**——ND2 一级 = 1.0（X7，
  * Logic Effort 方式）；0 = 纯布线。调度器据此计算加权深度 arrival(x) 并分桶；
  * clock 模式（每级组合延迟上限）同样以该口径度量。工艺特征化模型实现本 trait 即可接入。
  */
trait DelayModel {
  /** 模型名（日志/签名用）。 */
  def name: String
  /** 节点延迟代价（ND2 级数倍数，可为小数；0 = 纯布线）。 */
  def weight(n: Ir.Node): Double
  /** 模型自述权重表（X14：`toJson` 导出 / FDO 特征化模板用；宽度无关模型完全精确）。 */
  def entries: Seq[(String, Double)] = Seq.empty
}

object DelayModels {

  /** E1 加权表（默认，与历史行为逐字节一致；值恰为整数，Double 表示精确）。 */
  object Weighted extends DelayModel {
    val name: String = "weighted"
    def weight(n: Ir.Node): Double = n match {
      case _: Ir.Const | _: Ir.InputRef | _: Ir.Cat | _: Ir.Slice |
           _: Ir.Zext | _: Ir.Trunc | _: Ir.Not => 0.0
      case _: Ir.Bin | _: Ir.Mux => 1.0
      case _: Ir.RegRead => 2.0
    }
    override val entries: Seq[(String, Double)] = Seq(
      "Const" -> 0.0, "InputRef" -> 0.0, "Cat" -> 0.0, "Slice" -> 0.0, "Zext" -> 0.0,
      "Trunc" -> 0.0, "Not" -> 0.0, "Bin" -> 1.0, "Mux" -> 1.0, "RegRead" -> 2.0)
  }

  /** XLS unit 模型对标：叶子（Const/InputRef）为 0，其余每节点 1。 */
  object Unit extends DelayModel {
    val name: String = "unit"
    def weight(n: Ir.Node): Double = n match {
      case _: Ir.Const | _: Ir.InputRef => 0.0
      case _ => 1.0
    }
    override val entries: Seq[(String, Double)] = Seq(
      "Const" -> 0.0, "InputRef" -> 0.0, "Cat" -> 1.0, "Slice" -> 1.0, "Zext" -> 1.0,
      "Trunc" -> 1.0, "Not" -> 1.0, "Bin" -> 1.0, "Mux" -> 1.0, "RegRead" -> 1.0)
  }

  /** X7：Logic Effort 模型（Sutherland/Sproull/Harris），ND2 归一化。
    *
    * 单门 op：d = (g·h + p) / (g_ND2·h + p_ND2)，参考扇出 h = 1，其中
    * ND2 = g 4/3 + p 2 = 10/3 τ（INV = g 1 + p 1 = 2τ → 0.6；
    * NAND+INV（And/Or）= 1.6；XOR2 = g 4 + p 6 → 3.0；2:1 mux = 2.0（2026-09-06 校准）。
    *
    * 复合 op 按门网络级数展开（宽度 w 相关）：
    *   - Add/Sub：行波进位链上界，每 bit ≈ 1 ND2 → w（综合可能构建 CLA 更快，
    *     高估方向保守——切更多级只会更慢，不会违时序）；
    *   - Shl/Shr：桶形移位 = log2(w) 级 2:1 mux；
    *   - Eq/Neq：按位 XNOR（并行）+ AND 归约树；
    *   - Lt/Le/Gt/Ge：树形比较器近似。
    *
    * 已知简化：忽略扇出负载（g·h 的 h 取 1）与线电容；真实时序以综合 + STA 为准。
    */
  object LogicalEffort extends DelayModel {
    val name: String = "logiceffort"

    private def log2ceil(x: Int): Int = math.max(0, BigInt(math.max(0, x - 1)).bitLength)

    private def binW(op: Ir.Op, w: Int): Double = op match {
      case Ir.And | Ir.Or => 1.6 // NAND/NOR + INV
      case Ir.Xor => 3.0 // XOR2（按位并行，与 w 无关）
      case Ir.Add | Ir.Sub => w.toDouble // 行波进位链上界
      case Ir.Shl | Ir.Shr => 1.2 * log2ceil(w) // 桶形移位
      case Ir.Eq | Ir.Neq => 3.0 + 1.6 * log2ceil(w) // XNOR + AND 树
      case Ir.Lt | Ir.Le | Ir.Gt | Ir.Ge => 3.0 + 2.4 * log2ceil(w) // 树形比较器
    }
    private def regW(size: Int): Double =
      math.max(1.0, 1.2 * log2ceil(size)) // 读 mux 树：log2(size) 级 2:1 mux

    def weight(n: Ir.Node): Double = n match {
      case _: Ir.Const | _: Ir.InputRef | _: Ir.Cat | _: Ir.Slice |
           _: Ir.Zext | _: Ir.Trunc => 0.0 // 纯布线
      case _: Ir.Not => 0.6 // INV
      case _: Ir.Mux => 2 // 2:1 mux  Note:change by haoyu @20260906 from 1.2->2
      case Ir.Bin(op, _, _, w) => binW(op, w)
      case Ir.RegRead(_, _, _, size) => regW(size)
    }

    /** X14 导出表：宽度/规模相关项以常用档位（w ∈ 8/16/32/64、size ∈ 8/16/32）参数化
      * 键 `Bin(Add:16)` / `RegRead:8` 精确导出；无参键取 w=16 / size=8 基准值兜底
      * （加载端查找顺序：带参精确 → 无参 → 类型基准）。 */
    override val entries: Seq[(String, Double)] = {
      val ops: Seq[Ir.Op] =
        Seq(Ir.And, Ir.Or, Ir.Xor, Ir.Add, Ir.Sub, Ir.Shl, Ir.Shr, Ir.Eq, Ir.Neq, Ir.Lt, Ir.Le, Ir.Gt, Ir.Ge)
      val widths = Seq(8, 16, 32, 64)
      Seq(
        "Const" -> 0.0, "InputRef" -> 0.0, "Cat" -> 0.0, "Slice" -> 0.0, "Zext" -> 0.0,
        "Trunc" -> 0.0, "Not" -> 0.6, "Mux" -> 2.0,
        "Bin" -> binW(Ir.Add, 16), "RegRead" -> regW(8)) ++
        ops.map(op => s"Bin($op)" -> binW(op, 16)) ++
        widths.flatMap(w => ops.map(op => s"Bin($op:$w)" -> binW(op, w))) ++
        Seq(8, 16, 32).map(s => s"RegRead:$s" -> regW(s))
    }
  }

  val builtin: Map[String, DelayModel] =
    Map(Weighted.name -> Weighted, Unit.name -> Unit, LogicalEffort.name -> LogicalEffort)

  val default: DelayModel = Weighted

  private val requiredOps = Seq("Const", "InputRef", "Cat", "Slice", "Zext", "Trunc", "Not", "Bin", "Mux", "RegRead")

  /** 解析模型规格：内置名（weighted/unit/logiceffort，大小写不敏感）或 JSON 文件路径。
    *
    * JSON 形如 `{"Const":0,"InputRef":0,...,"Bin":1,"Mux":1.2,"RegRead":2}`（允许小数，
    * 即 ND2 倍数口径）；可用 `"Bin(Add)": 12` 按运算符细分、`"Bin(Add:16)"` /
    * `"RegRead:8"` 按宽度/规模参数化细分（查找顺序：带参精确 → 无参 → 类型基准，
    * X14 导出格式闭环）。缺少任一必需的**基准项** → [[P4Error]]（带模型路径与缺失项清单）。
    */
  def load(spec: String): DelayModel =
    builtin.get(spec.toLowerCase).getOrElse {
      val path = java.nio.file.Paths.get(spec)
      val txt = try new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)
      catch { case e: java.io.IOException => throw new P4Error(s"无法读取延迟模型文件 '$spec'：${e.getMessage}") }
      loadText(txt, spec)
    }

  /** 从 JSON 文本构建模型（[[load]] 的文件无关内核，供导出→回灌闭环直接使用）。
    *
    * 键正则（2026-09-08 修复两处匹配缺陷，勿回退）：
    *   - 不得有前导空格——须同时支持紧凑 `{"Const":0}` 与带空格格式；
    *   - 细分键的参数段 `(?::\d+)?` 必须可选——`Bin(Add)`（无宽参数）与
    *     `Bin(Add:16)`（带参）两种导出形态都要匹配，否则细分项丢失、回退基准值。
    */
  def loadText(txt: String, modelName: String): DelayModel = {
    val entries = """"(\w+(?:\(\w+(?::\d+)?\))?(?::\d+)?)"\s*:\s*(\d+(?:\.\d+)?)""".r
      .findAllMatchIn(txt).map(m => m.group(1) -> m.group(2).toDouble).toMap
    val missing = requiredOps.filterNot(entries.contains)
    if (missing.nonEmpty)
      throw new P4Error(s"延迟模型 '$modelName' 缺少权重项：${missing.mkString(", ")}")
    val loaded = entries
    new DelayModel {
      val name: String = modelName
      override val entries: Seq[(String, Double)] = loaded.toSeq.sortBy(_._1)
      def weight(n: Ir.Node): Double = n match {
        case Ir.Bin(op, _, _, w) =>
          loaded.getOrElse(s"Bin($op:$w)", loaded.getOrElse(s"Bin($op)", loaded("Bin")))
        case _: Ir.Const => loaded("Const")
        case _: Ir.InputRef => loaded("InputRef")
        case _: Ir.Cat => loaded("Cat")
        case _: Ir.Slice => loaded("Slice")
        case _: Ir.Zext => loaded("Zext")
        case _: Ir.Trunc => loaded("Trunc")
        case _: Ir.Not => loaded("Not")
        case _: Ir.Mux => loaded("Mux")
        case Ir.RegRead(_, _, _, size) =>
          loaded.getOrElse(s"RegRead:$size", loaded("RegRead"))
      }
    }
  }

  /** X14：模型 → JSON 模板（与 [[load]] 格式闭环：导出 → 工艺特征化修改 → 回灌）。
    * 宽度无关模型（weighted/unit 及加载模型）导出即精确；LogicalEffort 的宽度
    * 相关项以常用档位参数化键导出（见其 entries 说明）。 */
  def toJson(model: DelayModel): String = {
    val b = new StringBuilder
    b ++= "{\n"
    val es = model.entries
    es.zipWithIndex.foreach { case ((k, v), i) =>
      b ++= s"""  "$k": $v"""
      if (i < es.length - 1) b += ','
      b ++= "\n"
    }
    b ++= "}\n"
    b.toString
  }
}
