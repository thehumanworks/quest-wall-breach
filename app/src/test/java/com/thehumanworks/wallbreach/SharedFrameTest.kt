package com.thehumanworks.wallbreach

import com.thehumanworks.wallbreach.sim.SharedFrame
import com.thehumanworks.wallbreach.sim.V3
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedFrameTest {
  private fun assertV3(e: V3, a: V3, eps: Float = 1e-4f) {
    assertEquals("x", e.x, a.x, eps)
    assertEquals("y", e.y, a.y, eps)
    assertEquals("z", e.z, a.z, eps)
  }

  @Test
  fun roundTrip() {
    val f = SharedFrame.fromPose(V3(1.2f, 0.8f, -3f), V3(0.3f, -0.2f, 0.9f))
    val p = V3(-0.7f, 1.6f, 2.2f)
    assertV3(p, f.sharedToWorld(f.worldToShared(p)))
    val d = V3(0.1f, 0.5f, -0.8f).norm()
    assertV3(d, f.dirSharedToWorld(f.dirWorldToShared(d)))
  }

  @Test
  fun calibrationPointIsSharedOriginAndForwardIsPlusZ() {
    val f = SharedFrame.fromPose(V3(2f, 0.9f, 1f), V3(1f, 0f, 0f))
    assertV3(V3(0f, 0.9f, 0f), f.worldToShared(V3(2f, 0.9f, 1f)))
    assertV3(V3(0f, 0f, 1f), f.dirWorldToShared(V3(1f, 0f, 0f)))
  }

  /** Two headsets with different tracking origins see the same physical point at equal shared coords. */
  @Test
  fun twoHeadsetsAgree() {
    // Physical room -> headset A world: rotate 30deg + offset; headset B: rotate -100deg + other offset.
    fun toA(p: V3) = p.rotY((PI / 6).toFloat()) + V3(0.5f, 0f, -1f)
    fun toB(p: V3) = p.rotY((-PI * 100 / 180).toFloat()) + V3(-2f, 0f, 3f)
    val spot = V3(1f, 0.75f, 2f) // table corner
    val pointing = V3(0f, 0f, 1f) // both point the controller the same physical way
    val fa = SharedFrame.fromPose(toA(spot), toA(spot + pointing) - toA(spot))
    val fb = SharedFrame.fromPose(toB(spot), toB(spot + pointing) - toB(spot))
    for (p in listOf(V3(0f, 1.6f, 0f), V3(3f, 2f, -1f), V3(-2f, 0.2f, 4f))) {
      assertV3(fa.worldToShared(toA(p)), fb.worldToShared(toB(p)))
    }
  }
}
