import com.android.build.api.dsl.CommonExtension
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import io.github.gmazzo.test.aggregation.TestAggregationCoverageReport

buildscript {
    dependencies {
        classpath(libs.diffUtils)
    }
}

plugins {
    base
    jacoco
    id("io.github.gmazzo.test.aggregation")
}

reporting.reports.withType<TestAggregationCoverageReport>().configureEach {
    content {
        exclude("**/*ToBeExcluded*")
    }
}

dependencies {
    aggregateTestsFrom(projects.demoProject.app)
    aggregateTestsFrom(projects.demoProject.domain)
    aggregateTestsFrom(projects.demoProject.kmp)
    aggregateTestsFrom(projects.demoProject.login)
    aggregateTestsFrom(projects.demoProject.uiTests)
}

subprojects {
    plugins.withId("com.android.base") {
        the<CommonExtension>().testOptions.managedDevices.localDevices {
            configureEach {
                device = "Pixel 10"
                apiLevel = 33
                systemImageSource = "aosp_atd"
            }
            register("emulator")
            register("emulator2") {
                device = "Pixel 8"
                aggregateTests = false
            }
            register("emulator3") {
                device = "Pixel 9"
            }
        }
    }
}

val aggregatedReportsSpecs = layout.projectDirectory.dir("specs/aggregated-reports")

fun Sync.reportsSpec(): CopySpec {
    val rootDir = rootDir.absolutePath
    val dataSortRegEx = "\\bdata-sort-value=\"\\d+\"".toRegex()
    val tookRegEx = "\\b\\d+(?:\\.\\d+)?s\\b|(?<=\\btime=\")\\d+(?:\\.\\d+)?(?=\")".toRegex()
    val attrsRegEx = "\\b(timestamp|hostname)=\"[^\"]+\"\\s+".toRegex()
    val spansTimeRegEx =
        "\\d{4}-\\d?\\d-\\d?\\d \\d?\\d:\\d?\\d:\\d?\\d(?:\\.\\d+ \\w+)?".toRegex()
    val emulatorName = "\\bemulator-\\d+\\s*-?\\s*\\d*\\b".toRegex()
    val androidHome = providers.environmentVariable("ANDROID_HOME").get()
    val coverageTask = tasks.aggregatedTestCoverageReport
    val resultsTypes = tasks.aggregatedTestResultsReport

    return project.copySpec {
        into("coverage") {
            from(coverageTask) { include("**/*.csv") }
        }
        into("tests") {
            from(resultsTypes)
        }
        filter {
            when {
                it.startsWith("<a href=\"https://www.gradle.org\">") -> ""
                else -> it
                    .replace(attrsRegEx, "")
                    .replace(dataSortRegEx, "data-sort-value=\"100\"")
                    .replace(tookRegEx, "0.100s")
                    .replace(spansTimeRegEx, "2016-01-01 00:00")
                    .replace(emulatorName, "emulator-XXXX")
                    .replace(rootDir, "")
                    .replace(androidHome, "~/.android/sdk")
            }
        }
        includeEmptyDirs = false
        doLast {
            val cdataRegex = "<!\\[CDATA\\[.*?\\]\\]>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val preRegex = "<pre id=\".*\">.*?</pre>".toRegex(RegexOption.DOT_MATCHES_ALL)

            for (file in outputs.files.asFileTree) {
                when (file.extension) {
                    // makes sure CSV file is sorted alphabetically
                    "csv" -> file.writeText(
                        file
                            .readLines()
                            .let { it.take(1) + it.drop(1).sorted() }
                            .joinToString("\n")
                    )

                    // removes multiple CDATA
                    "xml" -> file.writeText(file
                        .readText()
                        .replace(cdataRegex, "<![CDATA[]]>")
                    )

                    // removes pre tags content
                    "html" -> file.writeText(file
                        .readText()
                        .replace(preRegex, "<pre id=\"...\">...</pre>")
                    )
                }
            }
        }
    }
}

tasks.register<Sync>("updateSpecs") {
    outputs.upToDateWhen { false }
    dependsOn(gradle.includedBuild("plugin").task(":updateSpecs"))
    with(reportsSpec())
    into(aggregatedReportsSpecs)
}

val checkReportsTask = tasks.register<Sync>("checkAggregatedReportsContent") {
    outputs.upToDateWhen { false }
    into("expects") {
        from(aggregatedReportsSpecs)
    }
    into("actual") {
        with(reportsSpec())
    }
    into(temporaryDir)
    doLast {
        fun File.collect() = walkTopDown()
            .filter(File::isFile)
            .associateBy { it.toRelativeString(this) }

        val expected = File(temporaryDir, "expects").collect()
        val actual = File(temporaryDir, "actual").collect()
        val diff = (expected.keys + actual.keys).mapNotNull {
            val expectedLines = expected[it]?.readLines().orEmpty()
            val actualLines = actual[it]?.readLines().orEmpty()

            when (actualLines) {
                expectedLines -> null
                else -> UnifiedDiffUtils.generateUnifiedDiff(
                    "expected:${it}", "actual:${it}",
                    expectedLines,
                    DiffUtils.diff(expectedLines, actualLines),
                    3
                ).joinToString("\n")
            }
        }
        check(diff.isEmpty()) {
            diff.joinToString(
                prefix = "The generated reports are different than the expected ones:\n",
                separator = "\n\n\n"
            )
        }
    }
}

tasks.check {
    dependsOn(checkReportsTask)
}
