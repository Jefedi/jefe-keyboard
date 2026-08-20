package ovh.jefe.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildWorkflowTest {
    @Test
    fun `CI verifies tests lint and assembly before uploading the APK`() {
        val workflow = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile ?: return@generateSequence null
        }
            .map { File(it, ".github/workflows/build.yml") }
            .first(File::isFile)
        val lines = workflow.readLines()
        val verificationIndex = lines.indexOfFirst {
            it.trimStart().startsWith("run:") && "./gradlew" in it
        }
        val uploadIndex = lines.indexOfFirst { "actions/upload-artifact" in it }
        val verificationCommand = lines[verificationIndex].substringAfter("run:").trim()

        assertEquals(
            "./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon",
            verificationCommand,
        )
        assertTrue("Verification must complete before artifact upload", verificationIndex < uploadIndex)
    }
}
