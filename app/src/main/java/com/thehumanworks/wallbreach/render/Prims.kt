package com.thehumanworks.wallbreach.render

import android.net.Uri
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Sphere
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.thehumanworks.wallbreach.sim.V3

fun V3.vec() = Vector3(x, y, z)

fun Vector3.v3() = V3(x, y, z)

/** Rotation whose local +Z axis points along [dir]. */
fun lookAlong(dir: V3, up: V3 = V3.UP): Quaternion {
  val d = dir.norm()
  if (d == V3.ZERO) return Quaternion()
  val u = if (kotlin.math.abs(d.dot(up)) > 0.98f) V3(1f, 0f, 0f) else up
  return Quaternion.lookRotation(d.vec(), u.vec())
}

/** Procedural primitives with glowing (unlit) materials: no external art assets needed. */
object Prims {
  fun glow(r: Float, g: Float, b: Float, a: Float = 1f): Material =
      Material().apply {
        baseColor = Color4(r, g, b, a)
        unlit = true
        alphaMode = if (a < 1f) AlphaMode.TRANSLUCENT.ordinal else AlphaMode.OPAQUE.ordinal
      }

  fun sphere(mat: Material, pos: V3 = V3.ZERO, scale: V3 = V3(0.1f, 0.1f, 0.1f), rot: Quaternion = Quaternion()): Entity =
      Entity.create(
          listOf(
              Mesh(Uri.parse("mesh://sphere")),
              Sphere(1f),
              mat,
              Transform(Pose(pos.vec(), rot)),
              Scale(scale.vec()),
              Hittable(MeshCollision.NoCollision),
              Visible(true),
          ),
      )

  fun box(mat: Material, pos: V3 = V3.ZERO, scale: V3 = V3(0.1f, 0.1f, 0.1f), rot: Quaternion = Quaternion()): Entity =
      Entity.create(
          listOf(
              Mesh(Uri.parse("mesh://box")),
              Box(Vector3(-0.5f), Vector3(0.5f)),
              mat,
              Transform(Pose(pos.vec(), rot)),
              Scale(scale.vec()),
              Hittable(MeshCollision.NoCollision),
              Visible(true),
          ),
      )

  fun place(e: Entity, pos: V3, rot: Quaternion = Quaternion(), scale: V3? = null) {
    e.setComponent(Transform(Pose(pos.vec(), rot)))
    if (scale != null) e.setComponent(Scale(scale.vec()))
  }

  fun uniform(s: Float) = V3(s, s, s)
}
