package com.thehumanworks.wallbreach.sim

import kotlin.math.atan2

/**
 * Co-location frame shared by both headsets.
 *
 * Each headset tracks in its own world space. During calibration each player puts their right
 * controller on the SAME physical spot, pointing the SAME way, and presses A. That pose defines a
 * frame (horizontal origin + yaw). Everything sent over the network is expressed in this shared
 * frame, so a point in the room has identical shared coordinates on both devices.
 *
 * Height (Y) is not offset: both headsets use a floor-level reference space, so Y already agrees.
 */
data class SharedFrame(val originX: Float = 0f, val originZ: Float = 0f, val yaw: Float = 0f) {
  fun worldToShared(p: V3): V3 = V3(p.x - originX, p.y, p.z - originZ).rotY(-yaw)
  fun sharedToWorld(s: V3): V3 = s.rotY(yaw).let { V3(it.x + originX, it.y, it.z + originZ) }
  fun dirWorldToShared(d: V3): V3 = d.rotY(-yaw)
  fun dirSharedToWorld(d: V3): V3 = d.rotY(yaw)

  companion object {
    val IDENTITY = SharedFrame()

    /** Build a frame from a calibration pose: position + forward direction (only yaw is used). */
    fun fromPose(position: V3, forward: V3): SharedFrame {
      val f = forward.flat().norm()
      val yaw = if (f == V3.ZERO) 0f else atan2(f.x, f.z)
      return SharedFrame(position.x, position.z, yaw)
    }
  }
}
