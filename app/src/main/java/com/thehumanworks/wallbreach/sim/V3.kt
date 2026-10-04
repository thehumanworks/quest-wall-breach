package com.thehumanworks.wallbreach.sim

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Tiny immutable 3D vector used by the engine-independent simulation and network code. */
data class V3(val x: Float, val y: Float, val z: Float) {
  operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
  operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
  operator fun times(s: Float) = V3(x * s, y * s, z * s)
  operator fun div(s: Float) = V3(x / s, y / s, z / s)
  operator fun unaryMinus() = V3(-x, -y, -z)
  fun dot(o: V3) = x * o.x + y * o.y + z * o.z
  fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
  fun lenSq() = x * x + y * y + z * z
  fun len() = sqrt(lenSq())
  fun norm(): V3 {
    val l = len()
    return if (l < 1e-6f) ZERO else this / l
  }
  fun flat() = V3(x, 0f, z)
  fun distSq(o: V3) = (this - o).lenSq()
  fun dist(o: V3) = sqrt(distSq(o))
  fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
  /** Rotate around the +Y axis by [rad] (right-handed, +Z maps to (sin, 0, cos)). */
  fun rotY(rad: Float): V3 {
    val c = cos(rad)
    val s = sin(rad)
    return V3(x * c + z * s, y, -x * s + z * c)
  }

  companion object {
    val ZERO = V3(0f, 0f, 0f)
    val UP = V3(0f, 1f, 0f)
    val FWD = V3(0f, 0f, 1f)
  }
}

/** Ray/sphere intersection. Returns distance along the (normalised) ray, or -1 if missed. */
fun raySphere(origin: V3, dir: V3, center: V3, radius: Float): Float {
  val oc = origin - center
  val b = oc.dot(dir)
  val c = oc.lenSq() - radius * radius
  val disc = b * b - c
  if (disc < 0f) return -1f
  val s = sqrt(disc)
  val t0 = -b - s
  if (t0 >= 0f) return t0
  val t1 = -b + s
  return if (t1 >= 0f) 0f else -1f
}

/** Shortest distance from point [p] to the ray (origin, normalised dir), clamped to the forward half. */
fun pointRayDistance(p: V3, origin: V3, dir: V3): Float {
  val t = (p - origin).dot(dir).coerceAtLeast(0f)
  return (origin + dir * t).dist(p)
}
