package com.thehumanworks.wallbreach

import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKPlane
import com.meta.spatial.toolkit.getAbsoluteTransform
import com.thehumanworks.wallbreach.render.v3
import com.thehumanworks.wallbreach.sim.PortalSite
import com.thehumanworks.wallbreach.sim.V3
import kotlin.math.abs

/** Converts MRUK scene-understanding walls into portal sites (device world frame). */
object RoomWalls {
  fun sitesFrom(mruk: MRUKFeature, head: V3): List<PortalSite> {
    val room = mruk.getCurrentRoom() ?: mruk.rooms.firstOrNull() ?: return emptyList()
    val out = ArrayList<PortalSite>()
    for (wall in room.walls) {
      val plane = wall.tryGetComponent<MRUKPlane>() ?: continue
      val pose = getAbsoluteTransform(wall)
      val q = pose.q
      val nRaw = (q * Vector3(0f, 0f, 1f)).v3()
      if (abs(nRaw.y) > 0.5f) continue // not a vertical surface
      val ax = (q * Vector3(1f, 0f, 0f)).v3()
      val ay = (q * Vector3(0f, 1f, 0f)).v3()
      val cx = (plane.min.x + plane.max.x) / 2f
      val cy = (plane.min.y + plane.max.y) / 2f
      val center = pose.t.v3() + ax * cx + ay * cy
      // plane axes are normally x = along the wall, y = up; handle the swapped case defensively
      val horizontalIsX = abs(ax.y) < abs(ay.y)
      val halfW = (if (horizontalIsX) plane.max.x - plane.min.x else plane.max.y - plane.min.y) / 2f
      val halfH = (if (horizontalIsX) plane.max.y - plane.min.y else plane.max.x - plane.min.x) / 2f
      if (halfW < 0.35f || halfH < 0.35f) continue
      var n = nRaw.flat().norm()
      if (n.dot(head - center) < 0f) n = -n // make sure the normal faces into the room
      val right = V3.UP.cross(n).norm()
      out += PortalSite(center, n, right, halfW, center.y - halfH, center.y + halfH)
    }
    return out
  }
}
