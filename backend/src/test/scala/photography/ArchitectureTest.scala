package photography

import com.tngtech.archunit.core.importer.{ClassFileImporter, ImportOption}
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import munit.FunSuite

class ArchitectureTest extends FunSuite:

  // `DO_NOT_INCLUDE_TESTS` only recognises Maven/Gradle/IntelliJ test layouts, not this build's `*-tests.jar`, so it's ineffective here.
  private val excludeTestClasses: ImportOption = location => !location.contains("-tests.jar")

  private val classes = ClassFileImporter().withImportOption(excludeTestClasses).importPackages("photography")

  test("should keep layers pointing inward and adapters isolated from each other"):
    noClasses()
      .that()
      .resideInAPackage("photography.domain..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "photography.capabilities..",
        "photography.ports..",
        "photography.usecases..",
        "photography.adapters..",
        "photography.app.."
      )
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.capabilities..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("photography.domain..", "photography.ports..", "photography.usecases..", "photography.adapters..", "photography.app..")
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.ports..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("photography.capabilities..", "photography.usecases..", "photography.adapters..", "photography.app..")
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.usecases..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("photography.adapters..", "photography.app..")
      .check(classes)
    slices().matching("photography.adapters.(*)..").should().notDependOnEachOther().check(classes)
