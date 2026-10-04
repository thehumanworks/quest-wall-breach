package com.thehumanworks.wallbreach

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins down the Spatial SDK math conventions the renderer relies on (pure-Kotlin SDK classes). */
class SdkMathConventionTest {
  @Test
  fun lookRotationMapsPlusZToForward() {
    for (d in listOf(Vector3(1f, 0f, 0f), Vector3(0f, 0f, -1f), Vector3(0.6f, 0.3f, -0.74f).normalize())) {
      val q = Quaternion.lookRotation(d, Vector3(0f, 1f, 0f))
      val f = q * Vector3(0f, 0f, 1f)
      assertEquals(d.x, f.x, 1e-3f)
      assertEquals(d.y, f.y, 1e-3f)
      assertEquals(d.z, f.z, 1e-3f)
      assertEquals(d.x, Pose(Vector3(0f), q).forward().x, 1e-3f)
    }
  }

  @Test
  fun lookRotationAroundYFacesAlongDirection() {
    val q = Quaternion.lookRotationAroundY(Vector3(1f, 0f, 0f))
    val f = q * Vector3(0f, 0f, 1f)
    println("lookRotationAroundY(+X) * +Z = $f")
  }
}
