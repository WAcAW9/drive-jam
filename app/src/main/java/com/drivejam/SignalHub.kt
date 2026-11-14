package com.drivejam

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener

/** Latest car state. Written by SDK callbacks / DEV controls, read by the UI ticker and the audio thread. */
class DriveState {
    @Volatile var turn = 0            // -1 left, 0 off, 1 right
    @Volatile var steerDeg = 0f       // -540..540
    @Volatile var brake = false
    @Volatile var rain = 0f           // 0 off, 0.5 intermittent/auto, 1 continuous
    @Volatile var night = false       // low beam on
    @Volatile var driveMode = 0       // -1 eco, 0 normal, 1 sport
    @Volatile var gear = "-"
    @Volatile var speedKmh = 0f
    @Volatile var speedFromDev = false
    @Volatile var scratchUntil = 0L   // hard-brake "record scratch" window (epoch ms)
    @Volatile var link = "연결 중"
    val steer get() = steerDeg / 540f
}

/**
 * The only place that talks to the Vehicle SDK (Vehicle State domains).
 * Unsupported or failing signals only switch off their own layer; everything else keeps playing.
 */
class SignalHub(private val ctx: Context, private val s: DriveState) {
    private var vehicle: Vehicle? = null
    private val offs = mutableListOf<() -> Unit>()
    private val failed = mutableSetOf<String>()

    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private var lastTripKm = -1f
    private var lastTripAt = 0L
    private val speedLog = ArrayDeque<Pair<Long, Float>>()

    fun start() {
        val v = try {
            Vehicle(ctx).also { it.initialize() }
        } catch (e: Throwable) {
            s.link = "Vehicle SDK 연결 실패 (${e.javaClass.simpleName}) — DEV 컨트롤 사용"
            return
        }
        vehicle = v
        s.link = "Vehicle SDK 연결됨"

        val turn = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
                s.turn = when (value) { TurnSignalValue.LEFT -> -1; TurnSignalValue.RIGHT -> 1; else -> 0 }
            }
            override fun onFailed(e: Exception) = fail("깜빡이", e)
        }
        reg("깜빡이", { v.getTurnSignal().registerTurnSignalState(turn) }, { v.getTurnSignal().unregisterTurnSignalState(turn) })

        val steer = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) { s.steerDeg = angle ?: 0f }
            override fun onFailed(e: Exception) = fail("핸들", e)
        }
        reg("핸들", { v.getSteeringWheel().registerSteeringWheelAngle(steer) }, { v.getSteeringWheel().unregisterSteeringWheelAngle(steer) })

        val brake = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) { s.brake = pressed == true }
            override fun onFailed(e: Exception) = fail("브레이크", e)
        }
        reg("브레이크", { v.getBrake().registerBrakePedalStatus(brake) }, { v.getBrake().unregisterBrakePedalStatus(brake) })

        val wiper = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) {
                if (state == WiperState.ON) readWiperSwitch(v) else s.rain = 0f
            }
            override fun onFailed(e: Exception) = fail("와이퍼", e)
        }
        reg("와이퍼", { v.getWiper().registerWiperState(front, wiper) }, { v.getWiper().unregisterWiperState(front, wiper) })
        readWiperSwitch(v)

        val lowBeam = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) { s.night = state == LightState.ON }
            override fun onFailed(e: Exception) = fail("전조등", e)
        }
        reg("전조등", { v.getLight().registerLowBeamLightState(lowBeam) }, { v.getLight().unregisterLowBeamLightState(lowBeam) })

        val mode = object : DrivingModeStateListener {
            override fun onDrivingModeStateUpdated(type: DrivingModeType?) {
                s.driveMode = when (type) { DrivingModeType.ECO -> -1; DrivingModeType.SPORT -> 1; else -> 0 }
            }
            override fun onFailed(e: Exception) = fail("주행모드", e)
        }
        reg("주행모드", { v.getDrivingMode().registerDrivingModeState(mode) }, { v.getDrivingMode().unregisterDrivingModeState(mode) })

        val gear = object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) { s.gear = g?.let(::gearLabel) ?: "-" }
            override fun onFailed(e: Exception) = fail("기어", e)
        }
        reg("기어", { v.getGear().registerGearSelection(gear) }, { v.getGear().unregisterGearSelection(gear) })

        // No speed API: derive speed from the trip odometer (Δdistance / Δtime).
        // Odometer is polled in tick(): change events are not guaranteed (none arrive on the emulator).
    }

    private var lastPoll = 0L

    private fun pollOdometer(now: Long) {
        val v = vehicle ?: return
        if (now - lastPoll < 500) return
        lastPoll = now
        runCatching {
            v.getOdometer().getCurrentDistanceDriven({ km: Float? ->
                if (km != null) onTrip(km)
            }, { e: Exception -> fail("주행거리", e) })
        }
    }

    /** Called by the UI ticker (~10 Hz): hard-brake detection from the speed history. */
    fun tick(now: Long) {
        pollOdometer(now)
        speedLog.addLast(now to s.speedKmh)
        while (speedLog.isNotEmpty() && now - speedLog.first().first > 1500) speedLog.removeFirst()
        val peak = speedLog.maxOf { it.second }
        // ponytail: 25 km/h drop in 1.5 s (~0.5 g) while braking = hard brake; tune on a real car.
        if (s.brake && peak - s.speedKmh >= 25f && now > s.scratchUntil + 4000) {
            s.scratchUntil = now + 600
            Log.i("DriveJam", "hard brake ${peak.toInt()} -> ${s.speedKmh.toInt()} km/h")
        }
    }

    fun release() {
        offs.forEach { runCatching(it) }
        offs.clear()
        runCatching { vehicle?.release() }
        vehicle = null
    }

    val failures: Set<String> get() = failed

    private fun onTrip(km: Float) {
        val now = System.currentTimeMillis()
        if (lastTripKm >= 0f && now - lastTripAt >= 500 && !s.speedFromDev) {
            val inst = (km - lastTripKm) / ((now - lastTripAt) / 3_600_000f)
            if (inst in 0f..250f) s.speedKmh += (inst - s.speedKmh) * 0.6f
        }
        if (lastTripKm < 0f || now - lastTripAt >= 500) { lastTripKm = km; lastTripAt = now }
    }

    private fun readWiperSwitch(v: Vehicle) = runCatching {
        v.getWiper().getCurrentWiperSwitch(front, { sw: WiperSwitch? ->
            s.rain = when (sw) {
                null, WiperSwitch.OFF, WiperSwitch.OTHER, WiperSwitch.SERVICE -> 0f
                WiperSwitch.CONTINUOUS_LEVEL_1, WiperSwitch.CONTINUOUS_LEVEL_2, WiperSwitch.CONTINUOUS_LEVEL_3,
                WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5 -> 1f
                else -> 0.5f
            }
        }, { e: Exception -> fail("와이퍼 단계", e) })
    }

    private fun gearLabel(g: VehicleGear) = when (g) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_UNKNOWN -> "-"
        else -> "D"
    }

    private fun reg(name: String, on: () -> Unit, off: () -> Unit) {
        try {
            on(); offs += off
        } catch (e: Throwable) {
            fail(name, e)
        }
    }

    private fun fail(name: String, e: Throwable) {
        Log.w("DriveJam", "signal failed: $name", e)
        failed += "$name(${e.message ?: e.javaClass.simpleName})"
    }
}
