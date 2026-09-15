package P4C

import scala.collection.mutable

/** 切拍调度 pass（D2 分桶 + E1 加权延时模型）。
  *
  * 产出调度标注（[[Ir.Dag.stages]]），不修改 DAG 本体。
  *
  * 调用时序约定（关键）：
  *   - 必须在 [[Passes.runAll]]（constFold → simplify → balance → narrow → cse → dce）
  *     之后调用：优化 pass 会重建并重编号 NodeId，先调度后优化会作废 stages 映射；
  *   - 调度之后不得再跑 CSE 等结构等价 pass：CSE 以节点结构去重，会把"结构相同但
  *     落在不同级"的节点跨级合并，产生级间数据依赖错误。
  *
  * E1 加权延时模型（替代均匀深度分桶，默认启用）：
  *   - 节点代价 weight：Cat / Slice / Zext / Trunc / Not / Const / InputRef = 0
  *     （纯布线/零逻辑）；Bin（全部算术/比较/移位/逻辑）= 1；Mux = 1；
  *     RegRead = 2（存储读延迟）；
  *   - 加权深度 wd(n) = weight(n) + max(wd(操作数))（无操作数 = weight）；
  *   - W = 所有可达节点的 wd 最大值；**W = 0（全布线 DAG，如只有 Cat/Slice）时
  *     不存在任何需要切开的逻辑级，等同 budget=1 直接不调度**（避免除零，也避免
  *     生成只有一级的无意义流水）；
  *   - n = min(budget, W+1)（加权深度不足预算时自然降级，不报错）；
  *   - stage(x) = min(n-1, wd(x) * n / (W+1))：把加权深度区间 [0, W] 均匀映射到
  *     [0, n-1]（整数除法）。映射单调 ⇒ 操作数所在级恒 ≤ 使用者所在级；
  *   - 所有 Sink（OutputWrite/RegWrite/CounterAdd）固定末级 n-1（发射约定，不进
  *     stages map，见 [[ChiselBackend.StagedEmitter]]）。
  *
  * `weighted = false` 保留旧的均匀深度分桶（Const/InputRef 深度 0，其余
  * depth = 1 + max(操作数深度)），仅作测试对照，不用于生产路径。
  *
  * X7：加权值升级为 **Logic Effort 口径**（[[DelayModel]]）——节点代价 = 相对
  * ND2（二输入 NAND，归一化 1.0）的延迟倍数，可为小数；内置 `logiceffort` 模型
  * 按 LE 理论取值（INV 0.6 / 2:1 mux 2.0（2026-09-06 校准）/ XOR2 3.0 /
  * 加法器 w 级行波上界…），clock 模式的预算即"每拍可容纳的 ND2 级数"。
  * 整数模型（weighted/unit）在 Double 运算下与历史结果逐点一致
  * （floor(d·n/(W+1)) = 整数除法截断）。
  */
object Scheduler {

  /** budget == 1 时原样返回（stages 空 = 未调度 = 全组合单拍）；否则调度。
    * ctx 用于错误信息定位（如 "control Ingress/action bump"）。
    * weighted=false 保留旧均匀深度分桶（测试对照）；model 仅在加权路径生效（X6）。
    * refine=true 时调度后追加 X10 寄存器最小化精化（clockW 给定时同时约束每级延迟）。 */
  def maybeSchedule(
    dag: Ir.Dag, budget: Int, ctx: String = "", weighted: Boolean = true,
    model: DelayModel = DelayModels.default, refine: Boolean = false, clockW: Option[Int] = None,
  ): Ir.Dag =
    if (budget == 1) dag else schedule(dag, budget, ctx, weighted, model, refine, clockW)

  /** 节点逻辑代价（由 [[DelayModel]] 提供，X7 Logic Effort 模型为小数 ND2 倍数）。 */
  private def weight(n: Ir.Node, model: DelayModel): Double = model.weight(n)

  /** 按加权深度（默认）或均匀深度（weighted=false，测试对照）分桶调度，
    * 返回带 stages 标注的 DAG（节点集合不变）。
    *
    * X7：加权深度与分桶升级为 Double（Logic Effort 倍数可为小数）；对整数
    * 模型（weighted/unit）Double 运算精确，`floor(d·n/(W+1))` 与原整数除法
    * 截断逐点一致——历史输出零回归。
    *
    * X10：refine=true 时在分桶结果上做 SDC-lite 局部搜索（寄存器最小化），
    * 约束：单调性 / 级数不变 / clockW 给定时每级到达延迟不超约束。
    */
  def schedule(
    dag: Ir.Dag, budget: Int, ctx: String = "", weighted: Boolean = true,
    model: DelayModel = DelayModels.default, refine: Boolean = false, clockW: Option[Int] = None,
  ): Ir.Dag = {
    val where = if (ctx.isEmpty) "" else s"$ctx："
    if (budget < 1) throw new P4Error(s"${where}拍数预算 N 必须 ≥ 1（got $budget）")
    if (budget == 1) return dag

    // 1. 可达性：从 outputs 出发 DFS，只调度可达节点（与 dce 的遍历模式一致）
    val reach = mutable.BitSet.empty
    def visit(id: Ir.NodeId): Unit =
      if (!reach(id)) {
        reach(id) = true
        Ir.operands(dag.nodes(id)).foreach(visit)
      }
    dag.outputs.foreach(Ir.visitSink(_, visit))
    if (reach.isEmpty) return dag // 空 DAG（如空 action）：无可调度节点

    // 2. 深度（memoized）。NodeId 升序即拓扑序（Builder 追加构造，操作数恒为更早节点）。
    //    weighted：wd = weight + max(wd(操作数))；unweighted：旧公式（Const/InputRef 0，其余 1+max）。
    val depth = mutable.HashMap.empty[Ir.NodeId, Double]
    (0 until dag.nodes.length).foreach { id =>
      if (reach(id)) {
        val base =
          if (weighted) weight(dag.nodes(id), model)
          else dag.nodes(id) match {
            case _: Ir.Const | _: Ir.InputRef => 0.0
            case _ => 1.0
          }
        depth(id) = base + (Ir.operands(dag.nodes(id)) match {
          case Seq() => 0.0
          case ops => ops.map(depth).max
        })
      }
    }
    val maxDepth = depth.values.max

    // E1：W=0 —— 全布线 DAG（可达节点全部零代价），没有可切的逻辑级：
    // 等同 budget=1，直接不调度（stages 保持空，走原组合发射路径；同时避免
    // 下面的分母 W+1 无意义地产生全 0 级"伪流水"）。
    if (weighted && maxDepth == 0.0) return dag

    // 3. 均匀分桶：stage(id) = min(n-1, floor(depth(id) * n / (W+1)))
    val nStages = math.min(budget, math.ceil(maxDepth).toInt + 1)
    val denom = maxDepth + 1.0
    val stageMap: Map[Ir.NodeId, Int] = depth.toMap.map { case (id, d) =>
      id -> math.min(nStages - 1, math.floor(d * nStages / denom).toInt)
    }

    // 3.5 X10：SDC-lite 精化（寄存器最小化局部搜索）。
    // budget 模式（clockW 未给）不能无约束搬节点——否则全部挤进第 0 级可使
    // 寄存器最少，但该级组合延迟 = 全图关键路径，切拍名存实亡。以初始分桶的
    // 最大每级延迟为预算：精化只减寄存器、不加深任何一级（初始解天然可行）。
    val refined =
      if (refine) {
        val bw = clockW orElse Some(math.ceil(maxStageDelay(dag, stageMap, nStages, model) - 1e-9).toInt)
        refineStages(dag, stageMap, nStages, ctx, model, bw)
      } else stageMap

    // 4. D3 读-写校验（防御性断言：Sink 固定末级下结构上恒过，守护未来优化）
    checkReadWrite(dag, refined, dag.outputs.map(_ => nStages - 1), ctx)

    dag.copy(stages = refined)
  }

  /** 初始分桶的最大每级组合延迟（[[levelDelays]] 内核，供 refine 预算）。 */
  private def maxStageDelay(
    dag: Ir.Dag, stageMap: Map[Ir.NodeId, Int], nStages: Int, model: DelayModel,
  ): Double = levelDelays(dag, stageMap, nStages, model).max

  // ---------------- X10：SDC-lite 寄存器最小化（对标 XLS SDC 调度的 pipeline 寄存器目标） ----------------

  /** 可达节点的加权到达时间表（公开口径：X12 stats / X13 签名共用）。 */
  def arrivals(dag: Ir.Dag, model: DelayModel = DelayModels.default): Map[Ir.NodeId, Double] =
    depths(dag, model)

  /** 关键路径长度（最大到达延迟，ND2 口径；空 DAG = 0）。 */
  def criticalPath(dag: Ir.Dag, model: DelayModel = DelayModels.default): Double = {
    val d = depths(dag, model)
    if (d.isEmpty) 0.0 else d.values.max
  }

  /** 边界寄存器数（SDC 目标函数口径，与 StagedEmitter 的 crossing 判定一致）：
    * Σ_u max(0, maxConsumer(u) − stage(u))，Sink 恒视为末级。未调度 DAG = 0。 */
  def boundaryRegisters(dag: Ir.Dag): Int = {
    if (!dag.isScheduled) return 0
    val last = dag.stageCount - 1
    val maxCons = mutable.HashMap.empty[Ir.NodeId, Int]
    def bump(id: Ir.NodeId, st: Int): Unit = maxCons(id) = math.max(maxCons.getOrElse(id, st), st)
    dag.outputs.foreach(s => Ir.visitSink(s, id => bump(id, last)))
    dag.nodes.indices.foreach { id =>
      if (dag.stages.contains(id)) {
        val st = dag.stages(id)
        Ir.operands(dag.nodes(id)).foreach(op => bump(op, st))
      }
    }
    maxCons.keys.toSeq.map { u => math.max(0, maxCons(u) - dag.stages.getOrElse(u, 0)) }.sum
  }

  /** SDC-lite：分桶结果为初值，节点按逆拓扑序做**单级移动局部搜索**：
    * 候选区间 = [maxOpStage, minConsStage]（区间内移动不破坏单调性），取使边界
    * 寄存器总数最小的档位（并列取更晚——"尽量晚"惯例，靠近消费者减少寄存）；
    * 至不动点（有迭代上限）。级数不变（末级不许被搬空）；clockW 给定时每个
    * 候选须保持每级到达延迟 ≤ clockW。XLS SDC LP 精确解的保守近似。
    *
    * 目标函数 = [[boundaryRegisters]]：Σ_u max(0, maxConsumer(u) − stage(u))。
    * 单节点移动只影响自身项与其操作数项，局部代价与全局目标严格一致，
    * 每次接受移动目标严格下降 ⇒ 收敛（无环）。 */
  private def refineStages(
    dag: Ir.Dag, init: Map[Ir.NodeId, Int], nStages: Int, ctx: String,
    model: DelayModel, clockW: Option[Int],
  ): Map[Ir.NodeId, Int] = {
    val last = nStages - 1
    val reachable = init.keys.toSet // 分桶覆盖全部可达节点

    // 消费者表：u → 消费者节点列表（Sink 以 -1 标记，恒在末级）。级动态查表，无陈旧问题。
    val consumers = mutable.HashMap.empty[Ir.NodeId, mutable.ArrayBuffer[Int]]
    def addCons(u: Ir.NodeId, c: Int): Unit =
      consumers.getOrElseUpdate(u, mutable.ArrayBuffer.empty[Int]) += c
    dag.outputs.foreach(s => Ir.visitSink(s, u => addCons(u, -1)))
    dag.nodes.indices.foreach { id =>
      if (reachable(id)) Ir.operands(dag.nodes(id)).foreach(op => addCons(op, id))
    }

    val stages = mutable.HashMap.empty[Ir.NodeId, Int] ++ init

    def consStage(c: Int): Int = if (c < 0) last else stages(c)

    /** u 的最大/最小消费者级（exclude 剔除指定消费者，评估候选位用）。 */
    def maxCons(u: Ir.NodeId, exclude: Int = -2): Int =
      consumers.get(u).fold(0)(_.filterNot(_ == exclude).map(consStage).fold(0)(math.max))
    def minCons(u: Ir.NodeId): Int =
      consumers.get(u).fold(last)(_.map(consStage).foldLeft(last)(math.min))

    /** u 置于 s 的局部代价（= 自身项 + 各操作数项；与全局目标增量严格一致）。 */
    def localCost(u: Ir.NodeId, s: Int): Int = {
      val own = math.max(0, maxCons(u) - s)
      val ops = Ir.operands(dag.nodes(u)).map { op =>
        val opStage = stages.getOrElse(op, 0)
        math.max(0, math.max(maxCons(op, exclude = u), s) - opStage)
      }.sum
      own + ops
    }

    def delaysOk(): Boolean = clockW.forall { w =>
      levelDelays(dag, stages.toMap, nStages, model).forall(_ <= w)
    }

    var improved = true
    var iter = 0
    while (improved && iter < 8) {
      improved = false
      iter += 1
      (dag.nodes.length - 1 to 0 by -1).foreach { id => // 逆拓扑序
        if (reachable(id)) {
          val s0 = stages(id)
          val lo = Ir.operands(dag.nodes(id)).map(op => stages.getOrElse(op, 0)).foldLeft(0)(math.max)
          val hi = minCons(id)
          if (lo < hi) {
            // 末级不许被搬空（级数不变约束）
            val topAlone = s0 == last && stages.count(_._2 == last) <= 1
            val cands = (lo to hi).filterNot(s => s == s0 || (topAlone && s < last))
            // clockW 约束下的可行候选（临时试位后必还原）
            val feasible = cands.filter { s =>
              stages(id) = s
              val ok = delaysOk()
              stages(id) = s0
              ok
            }
            if (feasible.nonEmpty) {
              val best = feasible.minBy(s => (localCost(id, s), -s)) // 并列取更晚
              if (localCost(id, best) < localCost(id, s0)) {
                stages(id) = best
                improved = true
              }
            }
          }
        }
      }
    }
    stages.toMap
  }

  /** 可达节点的加权深度表 arrival(x)（与 [[schedule]] 加权路径同口径）。 */
  private def depths(dag: Ir.Dag, model: DelayModel): Map[Ir.NodeId, Double] = {
    val reach = mutable.BitSet.empty
    def visit(id: Ir.NodeId): Unit =
      if (!reach(id)) {
        reach(id) = true
        Ir.operands(dag.nodes(id)).foreach(visit)
      }
    dag.outputs.foreach(Ir.visitSink(_, visit))
    val depth = mutable.HashMap.empty[Ir.NodeId, Double]
    (0 until dag.nodes.length).foreach { id =>
      if (reach(id)) {
        depth(id) = weight(dag.nodes(id), model) + (Ir.operands(dag.nodes(id)) match {
          case Seq() => 0.0
          case ops => ops.map(depth).max
        })
      }
    }
    depth.toMap
  }

  /** clock 约束的结构下界：最大单节点延迟（单节点不可再切分，向上取整到整数
    * ND2 级——clock 预算为整数级数）。
    * clockW < minClock 时不存在任何可行调度（XLS minimize_clock_on_failure 的等价报告）。 */
  def minClock(dag: Ir.Dag, model: DelayModel = DelayModels.default): Int = {
    val d = depths(dag, model)
    if (d.isEmpty) return 0
    math.ceil(d.keys.map(id => model.weight(dag.nodes(id))).foldLeft(0.0)(math.max)).toInt
  }

  /** 调度结果的各级组合延迟（ND2 级数口径）：**级内相对路径**——
    * delay(k) = max{x∈k} levelPath(x)，levelPath(x) = weight(x) + max{levelPath(op) |
    * op 为 x 的操作数且 stage(op) = k}（同级传递闭包；跨级行入点/源节点重置为 0）。
    * 未调度 DAG 视为单级：delay = max(arrival)。
    *
    * 口径说明（X10 修正）：不能以绝对到达时间做差（max arrival − min start）——
    * 零权重源节点（Const/InputRef）搬入高级会把窗口虚拉到全图深度，拒绝合法
    * 移动（如常量并入末级）；级内相对路径只计本级行入后的逻辑，与切拍语义一致。 */
  def stageDelays(dag: Ir.Dag, model: DelayModel = DelayModels.default): Seq[Double] = {
    val d = depths(dag, model)
    if (d.isEmpty) return Seq(0.0)
    if (!dag.isScheduled) return Seq(d.values.max)
    levelDelays(dag, dag.stages, dag.stageCount, model)
  }

  /** levelPath 内核（NodeId 升序 = 拓扑序）。空级 = 0。 */
  private def levelDelays(
    dag: Ir.Dag, stages: Map[Ir.NodeId, Int], nStages: Int, model: DelayModel,
  ): Seq[Double] = {
    val lp = mutable.HashMap.empty[Ir.NodeId, Double]
    val perStage = Array.fill(nStages)(0.0)
    (0 until dag.nodes.length).foreach { id =>
      stages.get(id).foreach { k =>
        val ops = Ir.operands(dag.nodes(id))
        val opMax =
          if (ops.isEmpty) 0.0
          else ops.map(op => if (stages.get(op).contains(k)) lp.getOrElse(op, 0.0) else 0.0).max
        val v = model.weight(dag.nodes(id)) + opMax
        lp(id) = v
        perStage(k) = math.max(perStage(k), v)
      }
    }
    perStage.toSeq
  }

  /** clock 模式：给定每级可容纳的 ND2 级数上限 clockW（X7：Logic Effort 口径），
    * 求最小可行级数（线性扫描 1..floor(W)+1，取首个每级延迟 ≤ clockW 的 n；
    * 扫描而非二分，规避分桶映射的非严格单调性假设）。
    * clockW 低于 [[minClock]]（ceil 到整数级）时抛 [[P4Error]] 并报告最小可行周期。 */
  def minFeasibleStages(
    dag: Ir.Dag, clockW: Int, ctx: String = "", model: DelayModel = DelayModels.default,
  ): Int = {
    val where = if (ctx.isEmpty) "" else s"$ctx："
    if (clockW < 1) throw new P4Error(s"${where}clock 约束必须 ≥ 1（got $clockW）")
    val mc = minClock(dag, model)
    if (clockW < mc)
      throw new P4Error(s"${where}clock=$clockW 不可行：单节点最大延迟 $mc 级已超约束（最小可行 clock = $mc）")
    val d = depths(dag, model)
    if (d.isEmpty || d.values.max == 0.0) return 1 // 全布线 DAG / 空 DAG：无需切拍
    val maxW = d.values.max
    (1 to math.floor(maxW).toInt + 1).find { n =>
      stageDelays(schedule(dag, n, ctx, model = model), model).forall(_ <= clockW)
    }.getOrElse(math.floor(maxW).toInt + 1)
  }

  /** D3：RegRead 与同名 RegWrite/CounterAdd 跨级读-写次序校验。
    *
    * 语义约定：一次 DAG 调用内 RegRead 读旧值，所有写统一在末级提交（D3 不做写旁路）。
    * 若某 extern 实例的最大读级 > 其最小写级，读会读到本次调用的新值——编译期报
    * [[P4Error]]。
    *
    * 注意："Sink 固定末级"约定下写级恒为 n-1 ≥ 任意读级，本断言结构上不可能触发；
    * 保留以守护未来"Sink 提前到最深输入级"的优化（PRD P2 方向）不静默引入语义错误。
    *
    * @param sinkStages 各 Sink 的所在级，与 dag.outputs 一一对应；
    *                   Scheduler 按末级约定传 n-1，测试可注入人为映射验证断言。
    */
  def checkReadWrite(
    dag: Ir.Dag, stages: Map[Ir.NodeId, Int], sinkStages: Seq[Int], ctx: String = "",
  ): Unit = {
    val where = if (ctx.isEmpty) "" else s"$ctx："
    val minWrite = mutable.HashMap.empty[String, Int]
    dag.outputs.zip(sinkStages).foreach {
      case (r: Ir.RegWrite, st) => minWrite(r.inst) = math.min(minWrite.getOrElse(r.inst, Int.MaxValue), st)
      case (c: Ir.CounterAdd, st) => minWrite(c.inst) = math.min(minWrite.getOrElse(c.inst, Int.MaxValue), st)
      case _ =>
    }
    dag.nodes.indices.foreach { id =>
      dag.nodes(id) match {
        case Ir.RegRead(inst, _, _, _) if stages.contains(id) =>
          minWrite.get(inst).foreach { wSt =>
            val rSt = stages(id)
            if (rSt > wSt)
              throw new P4Error(
                s"${where}RegRead('$inst') 位于第 $rSt 级，但同名 RegWrite/CounterAdd 在第 $wSt 级写——" +
                  "跨级读-写次序破坏（会读到本次调用的新值）。D3 不做写旁路：请减小拍数预算或拆分 action")
          }
        case _ =>
      }
    }
  }
}
