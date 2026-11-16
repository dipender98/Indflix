package Test

import com.indstream.RejectedServers
import com.indstream.ServerFarm
import kotlin.test.Test
import kotlin.test.assertTrue

class RejectedServersTest {

    @Test
    fun rejectedServersStayOutOfFarm() {
        for (r in RejectedServers.all) {
            assertTrue(
                ServerFarm.allServers.none { it.id == r.id },
                "${r.id} must stay out of the farm",
            )
        }
    }

    @Test
    fun rejectedServersHaveReasons() {
        for (r in RejectedServers.all) {
            assertTrue(r.reason.isNotBlank(), "${r.id} needs a reason")
            assertTrue(r.category.isNotBlank(), "${r.id} needs a category")
        }
    }

    @Test
    fun rejectedIdsUnique() {
        val ids = RejectedServers.all.map { it.id }
        assertTrue(ids.size == ids.toSet().size, "rejected ids must be unique")
    }
}
