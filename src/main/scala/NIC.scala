package icenet

import chisel3._
import chisel3.util._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.devices.tilelink.TLROM
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.{HasRegMap, RegField}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.unittest.{UnitTest, UnitTestIO}
import freechips.rocketchip.util._
import testchipip.{StreamIO, StreamChannel, TLHelper}
import scala.math.max
import IceNetConsts._

/**
 * Configuration class to setup the NIC. This is configured by the user through
 * the user configuration classes/traits.
 *
 * @param NET_IF_WIDTH_BITS flit size in bits
 * @param NET_LEN_BITS length of the packet (the amt of bits needed to send the length)
 * @param mmioDataLenBits lend of the data given over MMIO
 * @param inBufPackets amt of packets to buffer
 * @param nMemXacts this is the amt of xacts that can be inflight
 * @param maxAcquireBytes this is the size of the data that you can get from mem
 *                        this is normally bounded by the $ line size
 * @param ctrlQueueDepth queue size for the CPU MMIO registers
 * @param tapFunc function used to route packets to different hardware module
 */
case class NICConfig(
  NET_IF_WIDTH_BITS: Int = 64,
  NET_LEN_BITS: Int = 16,
  mmioDataLenBits: Int = 64,
  inBufPackets: Int = 2,
  nMemXacts: Int = 8,
  maxAcquireBytes: Int = 64,
  ctrlQueueDepth: Int = 10){
    val NET_IF_WIDTH_BYTES = NET_IF_WIDTH_BITS/8
    def NET_FULL_KEEP = ~0.U(NET_IF_WIDTH_BYTES.W)
    val outBufFlits = 2 * (ETH_MAX_BYTES/NET_IF_WIDTH_BYTES)
}

/**
 * Case object to extend fields of NICConfig
 */
case object NICKey extends Field[NICConfig]

/**
 * Send IO for the NIC. It is the interfaces for the queues and the completed
 * data sendback.
 */
class IceNicSendIO(implicit val p: Parameters) extends Bundle {
  val req = Decoupled(UInt(p(NICKey).mmioDataLenBits.W))
  val comp = Flipped(Decoupled(Bool()))
}

/**
 * Receive IO for the NIC. It is the interfaces to send the request address
 * and the completed queue (returns the length of the data that was completed)
 */
class IceNicRecvIO(implicit val p: Parameters) extends Bundle {
  val req = Decoupled(UInt(p(NICKey).mmioDataLenBits.W))
  val comp = Flipped(Decoupled(UInt(p(NICKey).NET_LEN_BITS.W)))
}

/**
 * I/O bundle for sending/receiving from the NIC
 */
trait IceNicControllerBundle extends Bundle {
  implicit val p: Parameters
  val send = new IceNicSendIO
  val recv = new IceNicRecvIO
  val macAddr = Input(UInt(ETH_MAC_BITS.W))
}

/**
 * Controller module that fits in between the NIC and the CPU memory.
 * This sets up the structures to do MMIO using TL. It uses 4 queues to communicate
 * what addresses to read/put packet data and to signal completion. Also setup interrupts
 * when a completion queue is ready.
 */
trait IceNicControllerModule extends HasRegMap {
  implicit val p: Parameters
  val io: IceNicControllerBundle

  val sendCompDown = WireInit(false.B)

  val qDepth = p(NICKey).ctrlQueueDepth
  require(qDepth < (1 << 8))

  def queueCount[T <: Data](qio: QueueIO[T], depth: Int): UInt =
    TwoWayCounter(qio.enq.fire(), qio.deq.fire(), depth)

  // hold (len, addr) of packets that we need to send out
  val sendReqQueue = Module(new HellaQueue(qDepth)(UInt(p(NICKey).mmioDataLenBits.W)))
  val sendReqCount = queueCount(sendReqQueue.io, qDepth)
  // hold addr of buffers we can write received packets into
  val recvReqQueue = Module(new HellaQueue(qDepth)(UInt(p(NICKey).mmioDataLenBits.W)))
  val recvReqCount = queueCount(recvReqQueue.io, qDepth)
  // count number of sends completed
  val sendCompCount = TwoWayCounter(io.send.comp.fire(), sendCompDown, qDepth)
  // hold length of received packets
  val recvCompQueue = Module(new HellaQueue(qDepth)(UInt(p(NICKey).NET_LEN_BITS.W)))
  val recvCompCount = queueCount(recvCompQueue.io, qDepth)

  val sendCompValid = sendCompCount > 0.U
  val intMask = RegInit(0.U(2.W))

  io.send.req <> sendReqQueue.io.deq
  io.recv.req <> recvReqQueue.io.deq
  io.send.comp.ready := sendCompCount < qDepth.U
  recvCompQueue.io.enq <> io.recv.comp

  interrupts(0) := sendCompValid && intMask(0)
  interrupts(1) := recvCompQueue.io.deq.valid && intMask(1)

  val sendReqSpace = (qDepth.U - sendReqCount)
  val recvReqSpace = (qDepth.U - recvReqCount)

  def sendCompRead = (ready: Bool) => {
    sendCompDown := sendCompValid && ready
    (sendCompValid, true.B)
  }

  // setup MMIO for controller
  regmap(
    0x00 -> Seq(RegField.w(p(NICKey).mmioDataLenBits, sendReqQueue.io.enq)),
    0x08 -> Seq(RegField.w(p(NICKey).mmioDataLenBits, recvReqQueue.io.enq)),
    0x10 -> Seq(RegField.r(1, sendCompRead)),
    0x12 -> Seq(RegField.r(p(NICKey).NET_LEN_BITS, recvCompQueue.io.deq)),
    0x14 -> Seq(
      RegField.r(8, sendReqSpace),
      RegField.r(8, recvReqSpace),
      RegField.r(8, sendCompCount),
      RegField.r(8, recvCompCount)),
    0x18 -> Seq(RegField.r(ETH_MAC_BITS, io.macAddr)),
    0x20 -> Seq(RegField(2, intMask)))
}

/**
 * Params class for IceNIC Controller
 *
 * @param address address to use for MMIO
 * @param beatBytes size of a beat for TL
 */
case class IceNicControllerParams(address: BigInt, beatBytes: Int)

/**
 * Take commands from the CPU over TL2, expose as Queues
 */
class IceNicController(c: IceNicControllerParams)(implicit p: Parameters)
  extends TLRegisterRouter(
    c.address, "ice-nic", Seq("ucbbar,ice-nic"),
    interrupts = 2, beatBytes = c.beatBytes)(
      new TLRegBundle(c, _)    with IceNicControllerBundle)(
      new TLRegModule(c, _, _) with IceNicControllerModule)

/**
 * Send path is the combination of elements that make up the path for the NIC to send data over the
 * network. It consists of the reservation packet buffer, aligner, reader, and rate limiter.
 */
class IceNicSendPath(nInputTaps: Int = 0)(implicit p: Parameters)
    extends LazyModule {
  val reader = LazyModule(new IceNicReader)
  val node = TLIdentityNode()
  node := reader.node

  val config = p(NICKey)

  lazy val module = new LazyModuleImp(this) {
    val netConfig = new IceNetConfig(NET_IF_WIDTH_BITS = config.NET_IF_WIDTH_BITS)

    val io = IO(new Bundle {
      val send = Flipped(new IceNicSendIO)
      val tap = (nInputTaps > 0).option(
        Flipped(Vec(nInputTaps, Decoupled(new StreamChannel(config.NET_IF_WIDTH_BITS)))))
      val out = Decoupled(new StreamChannel(config.NET_IF_WIDTH_BITS))
      val rlimit = Input(new RateLimiterSettings(netConfig))
    })

    reader.module.io.send <> io.send

    val queue = Module(new ReservationBuffer(config.nMemXacts, config.outBufFlits, config.NET_IF_WIDTH_BITS))
    queue.io.alloc <> reader.module.io.alloc
    queue.io.in <> reader.module.io.out

    val aligner = Module(new Aligner(netConfig))
    aligner.io.in <> queue.io.out

    val unlimitedOut = if (nInputTaps > 0) {
      val arb = Module(new HellaPeekingArbiter(
        new StreamChannel(config.NET_IF_WIDTH_BITS), 1 + nInputTaps,
        (chan: StreamChannel) => chan.last, rr = true))
      arb.io.in <> (aligner.io.out +: io.tap.get)
      arb.io.out
    } else { aligner.io.out }

    val limiter = Module(new RateLimiter(new StreamChannel(config.NET_IF_WIDTH_BITS), netConfig))
    limiter.io.in <> unlimitedOut
    limiter.io.settings := io.rlimit
    io.out <> limiter.io.out
  }
}

/**
 * Send frames out
 */
class IceNicReader(implicit p: Parameters)
    extends LazyModule {
  val node = TLHelper.makeClientNode(
    name = "ice-nic-send", sourceId = IdRange(0, p(NICKey).nMemXacts))
  lazy val module = new IceNicReaderModule(this)
}

/**
 * Module to read data from the CPU memory based off the addresses and length given in
 * the send request queue from the controller.
 */
class IceNicReaderModule(outer: IceNicReader)
    extends LazyModuleImp(outer) {

  val config = p(NICKey)
  val maxBytes = config.maxAcquireBytes
  val nXacts = config.nMemXacts
  val outFlits = config.outBufFlits

  val io = IO(new Bundle {
    // I/O coming from the NIC Controller modules MMIO
    val send = Flipped(new IceNicSendIO)

    // leaving the module
    val alloc = Decoupled(new ReservationBufferAlloc(nXacts, outFlits))
    val out = Decoupled(new ReservationBufferData(nXacts, config.NET_IF_WIDTH_BITS))
  })

  val (tl, edge) = outer.node.out(0)
  val beatBytes = tl.params.dataBits / 8
  val byteAddrBits = log2Ceil(beatBytes)
  val addrBits = tl.params.addressBits
  val lenBits = config.NET_LEN_BITS - 1
  // indicator where the address is split into length and address
  val midPoint = config.mmioDataLenBits - config.NET_LEN_BITS
  // select the bits associated with the different parts of the packet data sent over
  // (aka get if it is a part, the len, and addr)
  val packpart = io.send.req.bits(config.mmioDataLenBits - 1)
  val packlen = io.send.req.bits(config.mmioDataLenBits - 2, midPoint)
  val packaddr = io.send.req.bits(midPoint - 1, 0)

  require(beatBytes == config.NET_IF_WIDTH_BYTES)

  // we allow one TL request at a time to avoid tracking
  val s_idle :: s_read :: s_comp :: Nil = Enum(3)
  val state = RegInit(s_idle)

  // physical (word) address in memory
  val sendaddr = Reg(UInt(addrBits.W))
  // number of words to send
  val sendlen  = Reg(UInt(lenBits.W))
  // 0 if last packet in sequence, 1 otherwise
  val sendpart = Reg(Bool())

  val xactBusy = RegInit(0.U(nXacts.W))
  val xactOnehot = PriorityEncoderOH(~xactBusy)
  val xactId = OHToUInt(xactOnehot)
  val xactLast = Reg(UInt(nXacts.W))
  val xactLeftKeep = Reg(Vec(nXacts, UInt(config.NET_IF_WIDTH_BYTES.W)))
  val xactRightKeep = Reg(Vec(nXacts, UInt(config.NET_IF_WIDTH_BYTES.W)))

  // size of the request to get from memory
  val reqSize = MuxCase(byteAddrBits.U,
    (log2Ceil(maxBytes) until byteAddrBits by -1).map(lgSize =>
        // use the largest size (beatBytes <= size <= maxBytes)
        // s.t. sendaddr % size == 0 and sendlen > size
        (sendaddr(lgSize-1,0) === 0.U &&
          (sendlen >> lgSize.U) =/= 0.U) -> lgSize.U))
  val isLast = (xactLast >> tl.d.bits.source)(0) && edge.last(tl.d)
  val canSend = state === s_read && !xactBusy.andR

  val loffset = Reg(UInt(byteAddrBits.W))
  val roffset = Reg(UInt(byteAddrBits.W))
  val lkeep = config.NET_FULL_KEEP << loffset
  val rkeep = config.NET_FULL_KEEP >> roffset
  val first = Reg(Bool())

  xactBusy := (xactBusy | Mux(tl.a.fire(), xactOnehot, 0.U)) &
                  ~Mux(tl.d.fire() && edge.last(tl.d),
                        UIntToOH(tl.d.bits.source), 0.U)

  val helper = DecoupledHelper(tl.a.ready, io.alloc.ready)

  io.send.req.ready := state === s_idle
  io.alloc.valid := helper.fire(io.alloc.ready, canSend)
  io.alloc.bits.id := xactId
  io.alloc.bits.count := (1.U << (reqSize - byteAddrBits.U))
  tl.a.valid := helper.fire(tl.a.ready, canSend)
  tl.a.bits := edge.Get(
    fromSource = xactId,
    toAddress = sendaddr,
    lgSize = reqSize)._2

  val outLeftKeep = xactLeftKeep(tl.d.bits.source)
  val outRightKeep = xactRightKeep(tl.d.bits.source)

  io.out.valid := tl.d.valid
  io.out.bits.id := tl.d.bits.source
  io.out.bits.data.data := tl.d.bits.data
  io.out.bits.data.keep := MuxCase(config.NET_FULL_KEEP, Seq(
    (edge.first(tl.d) && edge.last(tl.d)) -> (outLeftKeep & outRightKeep),
    edge.first(tl.d) -> outLeftKeep,
    edge.last(tl.d)  -> outRightKeep))
  io.out.bits.data.last := isLast
  tl.d.ready := io.out.ready
  io.send.comp.valid := state === s_comp
  io.send.comp.bits := true.B

  when (io.send.req.fire()) {
    val lastaddr = packaddr + packlen
    val startword = packaddr(midPoint-1, byteAddrBits)
    val endword = lastaddr(midPoint-1, byteAddrBits) +
                    Mux(lastaddr(byteAddrBits-1, 0) === 0.U, 0.U, 1.U)

    loffset := packaddr(byteAddrBits-1, 0)
    roffset := Cat(endword, 0.U(byteAddrBits.W)) - lastaddr
    first := true.B

    sendaddr := Cat(startword, 0.U(byteAddrBits.W))
    sendlen  := Cat(endword - startword, 0.U(byteAddrBits.W))
    sendpart := packpart
    state := s_read

    assert(packlen > 0.U, s"NIC packet length must be >0")
  }

  when (tl.a.fire()) {
    val reqBytes = 1.U << reqSize
    sendaddr := sendaddr + reqBytes
    sendlen  := sendlen - reqBytes
    when (sendlen === reqBytes) {
      xactLast := (xactLast & ~xactOnehot) | Mux(sendpart, 0.U, xactOnehot)
      xactRightKeep(xactId) := rkeep
      state := s_comp
    } .otherwise {
      xactLast := xactLast & ~xactOnehot
      xactRightKeep(xactId) := config.NET_FULL_KEEP
    }
    when (first) {
      first := false.B
      xactLeftKeep(xactId) := lkeep
    } .otherwise {
      xactLeftKeep(xactId) := config.NET_FULL_KEEP
    }
  }

  when (io.send.comp.fire()) {
    state := s_idle
  }
}

/**
 * Read in frames from NIC and write to memory
 */
class IceNicWriter(implicit p: Parameters) extends LazyModule {
  val nXacts = p(NICKey).nMemXacts
  val node = TLHelper.makeClientNode(
    name = "ice-nic-recv", sourceId = IdRange(0, nXacts))
  lazy val module = new IceNicWriterModule(this)
}

/**
 * Module to write the received packets from the network to the CPU memory based off                                                                      │ 15     state := s_idle                                                                                                                                      
 * the addresses given in the receive request queue in the controller.
 */
class IceNicWriterModule(outer: IceNicWriter)
    extends LazyModuleImp(outer) {

  val config = p(NICKey)

  val io = IO(new Bundle {
    val recv = Flipped(new IceNicRecvIO)
    val in = Flipped(Decoupled(new StreamChannel(config.NET_IF_WIDTH_BITS)))
    val length = Input(UInt(config.NET_LEN_BITS.W))
  })

  val maxBytes = p(NICKey).maxAcquireBytes
  val (tl, edge) = outer.node.out(0)
  val beatBytes = tl.params.dataBits / 8
  val fullAddrBits = tl.params.addressBits // total addr width in bits
  val byteAddrBits = log2Ceil(beatBytes) // # of bits for beatBytes
  val addrBits = fullAddrBits - byteAddrBits // addr that is smaller than beatBytes

  // assumed to be the same for the aligner to be correct
  require(beatBytes == config.NET_IF_WIDTH_BYTES)

  val s_idle :: s_data :: s_complete :: Nil = Enum(3)
  val state = RegInit(s_idle)

  val addrOffset = RegInit(0.U(byteAddrBits.W))
  val maskOffset = ~((~0.U(config.mmioDataLenBits.W)) << byteAddrBits.U)
  val streamShifter = Module(new StreamShifter(
    new IceNetConfig(NET_IF_WIDTH_BITS = config.NET_IF_WIDTH_BITS)))

  streamShifter.io.stream.in.valid := io.in.valid && (state === s_data)
  streamShifter.io.stream.in.bits := io.in.bits
  streamShifter.io.addrOffsetBytes := addrOffset

  val baseAddr = Reg(UInt(addrBits.W)) // base address of packet shift by beatBytes
  val idx = Reg(UInt(addrBits.W)) // idx skipping over NET_IF_WIDTH...
  val addrMerged = baseAddr + idx // final unshifted address

  val xactBusy = RegInit(0.U(outer.nXacts.W))
  val xactOnehot = PriorityEncoderOH(~xactBusy)

  val maxBeats = maxBytes / beatBytes
  val beatIdBits = log2Ceil(maxBeats)

  val beatsLeft = Reg(UInt(beatIdBits.W))
  val headAddr = Reg(UInt(addrBits.W))
  val headXact = Reg(UInt(log2Ceil(outer.nXacts).W))
  val headSize = Reg(UInt(log2Ceil(beatIdBits + 1).W))

  val newBlock = beatsLeft === 0.U
  val canSend = !xactBusy.andR || !newBlock

  // size of request to get
  val reqSize = MuxCase(0.U,
    (log2Ceil(maxBytes / beatBytes) until 0 by -1).map(lgSize =>
        (addrMerged(lgSize-1,0) === 0.U &&
          (io.length >> lgSize.U) =/= 0.U) -> lgSize.U))

  xactBusy := (xactBusy | Mux(tl.a.fire() && newBlock, xactOnehot, 0.U)) &
                  ~Mux(tl.d.fire(), UIntToOH(tl.d.bits.source), 0.U)

  val fromSource = Mux(newBlock, OHToUInt(xactOnehot), headXact)
  val toAddress = Mux(newBlock, addrMerged, headAddr) << byteAddrBits.U
  val lgSize = Mux(newBlock, reqSize, headSize) +& byteAddrBits.U

  // subtract extra bytes since the completed number is a multiple of flit size and you may
  // send less than a flit with the parameterized flit size
  val waitForStart = RegInit(false.B) // To keep track of when the state changes for the first time
  val subBytesRecvStart = RegInit(0.U(addrBits.W)) // If the req address is not aligned to the flit the
                                                   // output will be shifted to align the xact to the right addr.
                                                   // This accounts for the bytes not used in the first TL xact
  val subBytesRecvEnd = RegInit(0.U(addrBits.W)) // Used to keep track of the end of the packet and account for
                                                 // the amt of bytes not sent out on the last TL xact

  // output data over TL to CPU memory
  io.recv.req.ready := state === s_idle
  tl.a.valid := ((state === s_data) && streamShifter.io.stream.out.valid) && canSend
  tl.a.bits := edge.Put(
    fromSource = fromSource,
    toAddress = toAddress,
    lgSize = lgSize,
    data = streamShifter.io.stream.out.bits.data,
    mask = streamShifter.io.stream.out.bits.keep)._2
  tl.d.ready := xactBusy.orR
  io.in.ready := (state === s_data) && canSend && streamShifter.io.stream.in.ready
  streamShifter.io.stream.out.ready := (state === s_data) && canSend && tl.a.ready
  io.recv.comp.valid := state === s_complete && !xactBusy.orR
  io.recv.comp.bits := (idx << byteAddrBits.U) - subBytesRecvEnd - subBytesRecvStart

  when (io.recv.req.fire()) {
    idx := 0.U
    baseAddr := io.recv.req.bits >> byteAddrBits.U
    addrOffset := io.recv.req.bits & maskOffset
    beatsLeft := 0.U
    waitForStart := true.B
    state := s_data
  }

  when (tl.a.fire()) {
    when (newBlock) {
      val bytesToWrite = 1.U << reqSize
      beatsLeft := bytesToWrite - 1.U
      headAddr := addrMerged
      headXact := OHToUInt(xactOnehot)
      headSize := reqSize
    } .otherwise {
      beatsLeft := beatsLeft - 1.U
    }

    when (waitForStart) {
      waitForStart := false.B
      subBytesRecvStart := PopCount(config.NET_FULL_KEEP) - PopCount(streamShifter.io.stream.out.bits.keep)
    }

    idx := idx + 1.U
    when (io.in.bits.last) {
      subBytesRecvEnd := PopCount(config.NET_FULL_KEEP) - PopCount(streamShifter.io.stream.out.bits.keep)
      state := s_complete
    }
  }

  when (io.recv.comp.fire()) { state := s_idle }
}

/**
 * Recv frames
 */
class IceNicRecvPath(val tapFuncs: Seq[EthernetHeader => Bool] = Nil)
    (implicit p: Parameters) extends LazyModule {
  val writer = LazyModule(new IceNicWriter)
  val node = TLIdentityNode()
  node := writer.node
  lazy val module = new IceNicRecvPathModule(this)
}

/**
 * Receive path is the combination of elements that make up the path for the NIC to send data
 * to the CPU from the network. It consists of the network packet buffer, writer, and network tap.
 */
class IceNicRecvPathModule(outer: IceNicRecvPath)
    extends LazyModuleImp(outer) {
  val config = p(NICKey)

  val io = IO(new Bundle {
    val recv = Flipped(new IceNicRecvIO)
    val in = Flipped(Decoupled(new StreamChannel(config.NET_IF_WIDTH_BITS))) // input stream 
    val tap = outer.tapFuncs.nonEmpty.option(
      Vec(outer.tapFuncs.length, Decoupled(new StreamChannel(config.NET_IF_WIDTH_BITS))))
  })

  val buffer = Module(new NetworkPacketBuffer(config.inBufPackets, wordBytes = config.NET_IF_WIDTH_BYTES))
  buffer.io.stream.in <> io.in

  val writer = outer.writer.module
  writer.io.length := buffer.io.length
  writer.io.recv <> io.recv
  writer.io.in <> (if (outer.tapFuncs.nonEmpty) {
    val tap = Module(new NetworkTap(outer.tapFuncs, wordBytes = config.NET_IF_WIDTH_BYTES))
    tap.io.inflow <> buffer.io.stream.out
    io.tap.get <> tap.io.tapout
    tap.io.passthru
  } else { buffer.io.stream.out })
}

/**
 * Class to specify the NIC I/O
 *
 * @param netConfig passin network parameters
 */
class NICIO(val netConfig: IceNetConfig) extends StreamIO(netConfig.NET_IF_WIDTH_BITS) {
  val macAddr = Input(UInt(ETH_MAC_BITS.W))
  val rlimit = Input(new RateLimiterSettings(netConfig))
}

/* 
 * A simple NIC
 *
 * Expects ethernet frames (see below), but uses a custom transport 
 * (see ExtBundle)
 * 
 * Ethernet Frame format:
 *   8 bytes    |  6 bytes  |  6 bytes    | 2 bytes  | 46-1500B | 4 bytes
 * Preamble/SFD | Dest Addr | Source Addr | Type/Len | Data     | CRC
 * Gen by NIC   | ------------- from/to CPU --------------------| Gen by NIC
 *
 * For now, we exclude the Gen by NIC components since we're talking to a 
 * custom network.
 */
class IceNIC(address: BigInt, beatBytes: Int = 8,
    tapOutFuncs: Seq[EthernetHeader => Bool] = Nil,
    nInputTaps: Int = 0)
    (implicit p: Parameters) extends LazyModule {

  val control = LazyModule(new IceNicController(
    IceNicControllerParams(address, beatBytes)))
  val sendPath = LazyModule(new IceNicSendPath(nInputTaps))
  val recvPath = LazyModule(new IceNicRecvPath(tapOutFuncs))

  val mmionode = TLIdentityNode()
  val dmanode = TLIdentityNode()
  val intnode = control.intnode

  control.node := TLAtomicAutomata() := mmionode
  dmanode := TLWidthWidget(p(NICKey).NET_IF_WIDTH_BYTES) := sendPath.node
  dmanode := TLWidthWidget(p(NICKey).NET_IF_WIDTH_BYTES) := recvPath.node

  lazy val module = new LazyModuleImp(this) {
    val config = p(NICKey)

    val netConfig = new IceNetConfig(NET_IF_WIDTH_BITS = config.NET_IF_WIDTH_BITS)

    val io = IO(new Bundle {
      val ext = new NICIO(netConfig)
      val tapOut = tapOutFuncs.nonEmpty.option(
        Vec(tapOutFuncs.length, Decoupled(new StreamChannel(config.NET_IF_WIDTH_BITS))))
      val tapIn = (nInputTaps > 0).option(
        Flipped(Vec(nInputTaps, Decoupled(new StreamChannel(config.NET_IF_WIDTH_BITS)))))
    })

    sendPath.module.io.send <> control.module.io.send
    recvPath.module.io.recv <> control.module.io.recv

    // connect externally
    recvPath.module.io.in <> io.ext.in
    io.ext.out <> sendPath.module.io.out

    control.module.io.macAddr := io.ext.macAddr
    sendPath.module.io.rlimit := io.ext.rlimit

    io.tapOut.zip(recvPath.module.io.tap).foreach {
      case (a, b) => a <> b
    }
    sendPath.module.io.tap.zip(io.tapIn).foreach {
      case (a, b) => a <> b
    }
  }
}

/**
 * [TODO: SimNetwork Description]
 */
class SimNetwork(implicit p: Parameters) extends BlackBox {
  val netConfig = new IceNetConfig(NET_IF_WIDTH_BITS = p(NICKey).NET_IF_WIDTH_BITS)

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val net = Flipped(new NICIO(netConfig))
  })
}

/**
 * Mixin to make the PeripheryIceNIC
 */
trait HasPeripheryIceNIC  { this: BaseSubsystem =>
  private val address = BigInt(0x10016000)
  private val portName = "Ice-NIC"

  val icenic = LazyModule(new IceNIC(address, sbus.beatBytes))
  sbus.toVariableWidthSlave(Some(portName)) { icenic.mmionode }
  sbus.fromPort(Some(portName))() :=* icenic.dmanode
  ibus.fromSync := icenic.intnode
}

/**
 * Mixing to make a PeripheryIceNIC Implicit
 */
trait HasPeripheryIceNICModuleImp extends LazyModuleImp {
  val outer: HasPeripheryIceNIC
  implicit val p: Parameters
  val netConfig = new IceNetConfig(NET_IF_WIDTH_BITS = p(NICKey).NET_IF_WIDTH_BITS)

  val net = IO(new NICIO(netConfig))

  net <> outer.icenic.module.io.ext

  def connectNicLoopback(qDepth: Int = 64) {
    net.in <> Queue(net.out, qDepth)
    net.macAddr := PlusArg("macaddr")
    net.rlimit.inc := PlusArg("rlimit-inc", 1)
    net.rlimit.period := PlusArg("rlimit-period", 1)
    net.rlimit.size := PlusArg("rlimit-size", 8)
  }

  def connectSimNetwork(clock: Clock, reset: Bool) {
    val sim = Module(new SimNetwork)
    sim.io.clock := clock
    sim.io.reset := reset
    sim.io.net <> net
  }
}

/**
 * [TODO: NICIOvonly Description]
 *
 * @param netConfig passin network parameters
 */
class NICIOvonly(val netConfig: IceNetConfig) extends Bundle {
  val in = Flipped(Valid(new StreamChannel(netConfig.NET_IF_WIDTH_BITS)))
  val out = Valid(new StreamChannel(netConfig.NET_IF_WIDTH_BITS))
  val macAddr = Input(UInt(ETH_MAC_BITS.W))
  val rlimit = Input(new RateLimiterSettings(netConfig))
}

/**
 * Companion object to NICIOvonly
 */
object NICIOvonly {
  def apply(nicio: NICIO, netConfig: IceNetConfig): NICIOvonly = {
    val vonly = Wire(new NICIOvonly(netConfig))
    vonly.out.valid := nicio.out.valid
    vonly.out.bits  := nicio.out.bits
    nicio.out.ready := true.B
    nicio.in.valid  := vonly.in.valid
    nicio.in.bits   := vonly.in.bits
    assert(!vonly.in.valid || nicio.in.ready, "NIC input not ready for valid")
    nicio.macAddr := vonly.macAddr
    nicio.rlimit  := vonly.rlimit
    vonly
  }
}

/**
 * [TODO: HasPeripheryIceNICModuleImpValidOnly Description]
 */
trait HasPeripheryIceNICModuleImpValidOnly extends LazyModuleImp {
  implicit val p: Parameters
  val outer: HasPeripheryIceNIC
  val netConfig = new IceNetConfig(NET_IF_WIDTH_BITS = p(NICKey).NET_IF_WIDTH_BITS)
  val net = IO(new NICIOvonly(netConfig))

  net <> NICIOvonly(outer.icenic.module.io.ext, netConfig)
}

class IceNicTestSendDriver(
    sendReqs: Seq[(Int, Int, Boolean)],
    sendData: Seq[BigInt])(implicit p: Parameters) extends LazyModule {
  val node = TLHelper.makeClientNode(
    name = "test-send-driver", sourceId = IdRange(0, 1))

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO {
      val send = new IceNicSendIO
    })

    val (tl, edge) = node.out(0)
    val dataBits = tl.params.dataBits
    val beatBytes = dataBits / 8
    val byteAddrBits = log2Ceil(beatBytes)
    val lenBits = p(NICKey).NET_LEN_BITS - 1
    val addrBits = p(NICKey).NET_IF_WIDTH_BITS - p(NICKey).NET_LEN_BITS

    val (s_start :: s_write_req :: s_write_resp ::
         s_send :: s_done :: Nil) = Enum(5)
    val state = RegInit(s_start)

    val sendReqVec = VecInit(sendReqs.map {
      case (addr, len, part) => Cat(part.B, len.U(lenBits.W), addr.U(addrBits.W))})

    val sendReqAddrVec = VecInit(sendReqs.map{case (addr, _, _) => addr.U(addrBits.W)})
    val sendReqCounts = sendReqs.map { case (_, len, _) => len / beatBytes }
    val sendReqCountVec = VecInit(sendReqCounts.map(_.U))
    val sendReqBase = VecInit((0 +: (1 until sendReqs.size).map(
      i => sendReqCounts.slice(0, i).reduce(_ + _))).map(_.U(addrBits.W)))
    val maxMemCount = sendReqCounts.reduce(max(_, _))
    val totalMemCount = sendReqCounts.reduce(_ + _)

    require(totalMemCount == sendData.size)

    val sendDataVec = VecInit(sendData.map(_.U(dataBits.W)))

    val reqIdx = Reg(UInt(log2Ceil(sendReqs.size).W))
    val memIdx = Reg(UInt(log2Ceil(totalMemCount).W))

    val outSend = TwoWayCounter(
      io.send.req.fire(), io.send.comp.fire(), sendReqs.size)

    val writeAddr = sendReqAddrVec(reqIdx) + (memIdx << byteAddrBits.U)
    val writeData = sendDataVec(sendReqBase(reqIdx) + memIdx)

    tl.a.valid := state === s_write_req
    tl.a.bits := edge.Put(
      fromSource = 0.U,
      toAddress = writeAddr,
      lgSize = byteAddrBits.U,
      data = writeData)._2
    tl.d.ready := state === s_write_resp

    io.send.req.valid := state === s_send
    io.send.req.bits := sendReqVec(reqIdx)
    io.send.comp.ready := outSend =/= 0.U

    io.finished := state === s_done && outSend === 0.U

    when (state === s_start && io.start) {
      reqIdx := 0.U
      memIdx := 0.U
      state := s_write_req
    }

    when (tl.a.fire()) { state := s_write_resp }

    when (tl.d.fire()) {
      memIdx := memIdx + 1.U
      state := s_write_req

      when (memIdx === (sendReqCountVec(reqIdx) - 1.U)) {
        memIdx := 0.U
        reqIdx := reqIdx + 1.U
        when (reqIdx === (sendReqs.size - 1).U) {
          reqIdx := 0.U
          state := s_send
        }
      }
    }

    when (io.send.req.fire()) {
      reqIdx := reqIdx + 1.U
      when (reqIdx === (sendReqs.size - 1).U) {
        reqIdx := 0.U
        state := s_done
      }
    }
  }
}

class IceNicTestRecvDriver(recvReqs: Seq[Int], recvData: Seq[BigInt])
    (implicit p: Parameters) extends LazyModule {

  val node = TLHelper.makeClientNode(
    name = "test-recv-driver", sourceId = IdRange(0, 1))

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO {
      val recv = new IceNicRecvIO
    })

    val (tl, edge) = node.out(0)
    val dataBits = tl.params.dataBits
    val beatBytes = dataBits / 8
    val byteAddrBits = log2Ceil(beatBytes)

    val (s_start :: s_recv :: s_wait ::
         s_check_req :: s_check_resp :: s_done :: Nil) = Enum(6)
    val state = RegInit(s_start)

    val recvReqVec = VecInit(recvReqs.map(_.U(p(NICKey).NET_IF_WIDTH_BITS.W)))
    val recvDataVec = VecInit(recvData.map(_.U(dataBits.W)))

    val reqIdx = Reg(UInt(log2Ceil(recvReqs.size).W))
    val memIdx = Reg(UInt(log2Ceil(recvData.size).W))

    val outRecv = TwoWayCounter(
      io.recv.req.fire(), io.recv.comp.fire(), recvReqVec.size)

    tl.a.valid := state === s_check_req
    tl.a.bits := edge.Get(
      fromSource = 0.U,
      toAddress = recvReqVec.head + (memIdx << byteAddrBits.U),
      lgSize = byteAddrBits.U)._2
    tl.d.ready := state === s_check_resp

    io.recv.req.valid := state === s_recv
    io.recv.req.bits := recvReqVec(reqIdx)
    io.recv.comp.ready := outRecv =/= 0.U

    io.finished := state === s_done

    when (state === s_start && io.start) {
      reqIdx := 0.U
      memIdx := 0.U
      state := s_recv
    }

    when (io.recv.req.fire()) {
      reqIdx := reqIdx + 1.U
      when (reqIdx === (recvReqVec.size - 1).U) {
        reqIdx := 0.U
        state := s_wait
      }
    }

    when (state === s_wait && outRecv === 0.U) {
      state := s_check_req
    }

    when (state === s_check_req && tl.a.ready) {
      state := s_check_resp
    }

    when (state === s_check_resp && tl.d.valid) {
      memIdx := memIdx + 1.U
      state := s_check_req
      when (memIdx === (recvData.size - 1).U) {
        memIdx := 0.U
        state := s_done
      }
    }

    assert(!tl.d.valid || tl.d.bits.data === recvDataVec(memIdx),
      "IceNicTest: Received wrong data")
  }
}

/**
 * Main test that receives data through the recv path and checks to make
 * sure that data was received correctly
 */
class IceNicRecvTest(implicit p: Parameters) extends LazyModule {
  val recvReqs = Seq(0, 1440, 1456)
  // the 90-flit packet should be dropped
  val recvLens = Seq(180, 2, 90, 8)
  val testData = Seq.tabulate(280) { i => BigInt(i << 4) }
  val testKeep = Seq.fill(testData.size) { BigInt(0xFF) }
  val recvData = testData.take(182) ++ testData.drop(272) // create test for 190B of data

  val config = p(NICKey)
  val recvDriver = LazyModule(new IceNicTestRecvDriver(recvReqs, recvData))
  val recvPath = LazyModule(new IceNicRecvPath)
  val xbar = LazyModule(new TLXbar)
  val mem = LazyModule(new TLRAM(
    AddressSet(0, 0x7ff), beatBytes = config.NET_IF_WIDTH_BYTES))

  val MEM_LATENCY = 32
  val RLIMIT_INC = 1
  val RLIMIT_PERIOD = 4
  val RLIMIT_SIZE = 8

  xbar.node := recvDriver.node
  xbar.node := recvPath.node
  mem.node := TLFragmenter(config.NET_IF_WIDTH_BYTES, config.maxAcquireBytes) :=
    TLHelper.latency(MEM_LATENCY, xbar.node)

  lazy val module = new LazyModuleImp(this) {

    val netConfig = new IceNetConfig(NET_IF_WIDTH_BITS = config.NET_IF_WIDTH_BITS)

    val io = IO(new Bundle with UnitTestIO)

    val gen = Module(new PacketGen(recvLens, testData, testKeep, netConfig))
    gen.io.start := io.start
    recvDriver.module.io.start := io.start
    recvPath.module.io.recv <> recvDriver.module.io.recv
    recvPath.module.io.in <> RateLimiter(
      gen.io.out, RLIMIT_INC, RLIMIT_PERIOD, RLIMIT_SIZE, netConfig)
    io.finished := recvDriver.module.io.finished
  }
}

class IceNicRecvTestWrapper(implicit p: Parameters) extends UnitTest(20000) {
  val test = Module(LazyModule(new IceNicRecvTest).module)
  test.io.start := io.start
  io.finished := test.io.finished
}

/**
 * Main test that sends data through the send path and checks to make sure
 * data was received correctly at the output.
 */
class IceNicSendTest(implicit p: Parameters) extends LazyModule {

  val netConfig = new IceNetConfig(NET_IF_WIDTH_BITS = p(NICKey).NET_IF_WIDTH_BITS)

  // consists of start addr, len of packet (bytes), and part (is this packet part of a sequence of packets)
  val sendReqs = Seq(
    (2, 10, true),
    (17, 6, false),
    (24, 12, false))

  // actual data to send over the NIC
  val sendData = Seq(
    BigInt("7766554433221100", 16),
    BigInt("FFEEDDCCBBAA9988", 16),
    BigInt("0123456789ABCDEF", 16),
    BigInt("FEDCBA9876543210", 16),
    BigInt("76543210FDECBA98", 16))

  // data to be sent out of the NIC
  val recvData = Seq(
    BigInt("9988776655443322", 16),
    BigInt("23456789ABCDBBAA", 16),
    BigInt("FEDCBA9876543210", 16),
    BigInt("00000000FDECBA98", 16))
  // bytemask indicating what recv bytes are valid
  val recvKeep = Seq.fill(sendData.size) { BigInt(0xFF) }
  // was is the last message send seq
  val recvLast = Seq(false, true, false, true)

  val sendPath = LazyModule(new IceNicSendPath)

  // this flatmap is creating an array of bytes where the BigInt from above is split into byte blocks
  val rom = LazyModule(new TLROM(0, 64,
    sendData.flatMap(
      data => (0 until 8).map(i => ((data >> (i * 8)) & 0xff).toByte)),
    beatBytes = 8))

  rom.node := TLFragmenter(p(NICKey).NET_IF_WIDTH_BYTES, 16) := TLBuffer() := sendPath.node

  // limiter constants
  val RLIMIT_INC = 1
  val RLIMIT_PERIOD = 0
  val RLIMIT_SIZE = 8

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO)

    val sendPathIO = sendPath.module.io

    // convert send req's to 64b words
    val sendReqVec = sendReqs.map { case (start, len, part) =>
      Cat(part.B, len.U(15.W), start.U(48.W))
    }
    // count when the req's are done and when the completion is signaled
    val (sendReqIdx, sendReqDone) = Counter(sendPathIO.send.req.fire(), sendReqs.size)
    val (sendCompIdx, sendCompDone) = Counter(sendPathIO.send.comp.fire(), sendReqs.size)

    val started = RegInit(false.B)
    val requesting = RegInit(false.B)
    val completing = RegInit(false.B)

    // decoupled I/O connections
    sendPathIO.send.req.valid := requesting
    sendPathIO.send.req.bits := sendReqVec(sendReqIdx)
    sendPathIO.send.comp.ready := completing

    // stop when the counters for request and completions has saturated
    when (!started && io.start) {
      requesting := true.B
      completing := true.B
    }
    when (sendReqDone)  { requesting := false.B }
    when (sendCompDone) { completing := false.B }

    // setup the limiter
    sendPathIO.rlimit.inc := RLIMIT_INC.U
    sendPathIO.rlimit.period := RLIMIT_PERIOD.U
    sendPathIO.rlimit.size := RLIMIT_SIZE.U

    // check that the received data matches with the data sent out of the send path
    val check = Module(new PacketCheck(recvData, recvKeep, recvLast, netConfig))
    check.io.in <> sendPathIO.out
    io.finished := check.io.finished && !completing && !requesting
  }
}

class IceNicSendTestWrapper(implicit p: Parameters) extends UnitTest {
  val test = Module(LazyModule(new IceNicSendTest).module)
  test.io.start := io.start
  io.finished := test.io.finished
}

/**
 * Test for noth send and receive path of the NIC
 */
class IceNicTest(implicit p: Parameters) extends LazyModule {
  // addr, len, part (is this a part of a larger sequence)
  val sendReqs = Seq(
    (0, 128, true),
    (144, 160, false),
    (320, 64, false))
  // lens that you should receive
  val recvReqs = Seq(256, 544)
  val testData = Seq.tabulate(44)(i => BigInt(i << 4))

  val config = p(NICKey)

  val sendDriver = LazyModule(new IceNicTestSendDriver(sendReqs, testData))
  val recvDriver = LazyModule(new IceNicTestRecvDriver(recvReqs, testData))
  val sendPath = LazyModule(new IceNicSendPath)
  val recvPath = LazyModule(new IceNicRecvPath)
  val xbar = LazyModule(new TLXbar)
  val mem = LazyModule(new TLRAM(
    AddressSet(0, 0x1ff), beatBytes = config.NET_IF_WIDTH_BYTES))

  val NET_LATENCY = 64
  val MEM_LATENCY = 32
  // rate limiter settings
  val RLIMIT_INC = 1
  val RLIMIT_PERIOD = 0
  val RLIMIT_SIZE = 8

  xbar.node := sendDriver.node
  xbar.node := recvDriver.node
  xbar.node := sendPath.node
  xbar.node := recvPath.node
  mem.node := TLFragmenter(config.NET_IF_WIDTH_BYTES, p(NICKey).maxAcquireBytes) :=
    TLHelper.latency(MEM_LATENCY, xbar.node)

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO)

    sendPath.module.io.send <> sendDriver.module.io.send
    recvPath.module.io.recv <> recvDriver.module.io.recv

    sendPath.module.io.rlimit.inc := RLIMIT_INC.U
    sendPath.module.io.rlimit.period := RLIMIT_PERIOD.U
    sendPath.module.io.rlimit.size := RLIMIT_SIZE.U

    recvPath.module.io.in <> LatencyPipe(sendPath.module.io.out, NET_LATENCY)

    sendDriver.module.io.start := io.start
    recvDriver.module.io.start := io.start
    io.finished := sendDriver.module.io.finished && recvDriver.module.io.finished

    val count_start :: count_up :: count_print :: count_done :: Nil = Enum(4)
    val count_state = RegInit(count_start)
    val cycle_count = Reg(UInt(64.W))
    val recv_count = Reg(UInt(1.W))

    when (count_state === count_start && sendPath.module.io.send.req.fire()) {
      count_state := count_up
      cycle_count := 0.U
      recv_count := 1.U
    }
    when (count_state === count_up) {
      cycle_count := cycle_count + 1.U
      when (recvPath.module.io.recv.comp.fire()) {
        recv_count := recv_count - 1.U
        when (recv_count === 0.U) { count_state := count_print }
      }
    }
    when (count_state === count_print) {
      printf("NIC test completed in %d cycles\n", cycle_count)
      count_state := count_done
    }
  }
}

class IceNicTestWrapper(implicit p: Parameters) extends UnitTest(50000) {
  val test = Module(LazyModule(new IceNicTest).module)
  test.io.start := io.start
  io.finished := test.io.finished
}
