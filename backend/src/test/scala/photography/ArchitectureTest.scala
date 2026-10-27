package photography

import cats.syntax.all.*
import com.tngtech.archunit.core.importer.{ClassFileImporter, ImportOption}
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import munit.FunSuite
import photography.domain.album.Album
import scala.jdk.CollectionConverters.*

class ArchitectureTest extends FunSuite:

  // sbt 2 uses content-addressed jars without a test suffix, so identify the test container by its actual code source.
  private val testCodeLocation                 = classOf[ArchitectureTest].getProtectionDomain.getCodeSource.getLocation.toURI.toString
  private val excludeTestClasses: ImportOption = location => !location.contains(testCodeLocation)

  private val classes = ClassFileImporter().withImportOption(excludeTestClasses).importPackages("photography")

  test("should keep layers pointing inward and adapters isolated from each other"):
    assertNotEquals(testCodeLocation, classOf[Album].getProtectionDomain.getCodeSource.getLocation.toURI.toString)
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
        "photography.app..",
        "doobie.."
      )
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.capabilities..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "photography.domain..",
        "photography.ports..",
        "photography.usecases..",
        "photography.adapters..",
        "photography.app..",
        "doobie.."
      )
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.ports..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("photography.capabilities..", "photography.usecases..", "photography.adapters..", "photography.app..", "doobie..")
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.usecases..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("photography.adapters..", "photography.app..", "doobie..")
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.adapters.sqlite..")
      .should()
      .dependOnClassesThat()
      .resideInAPackage("photography.usecases..")
      .check(classes)
    slices().matching("photography.adapters.(*)..").should().notDependOnEachOther().check(classes)
    noClasses().that().resideInAPackage("photography.domain..").should().dependOnClassesThat().haveSimpleName("Ordering").check(classes)
    val capabilities = Set(
      "cats.Monad",
      "cats.MonadError",
      "cats.effect.kernel.Async",
      "cats.effect.kernel.MonadCancel",
      "cats.effect.std.Mutex",
      "org.typelevel.log4cats.Logger",
      "photography.capabilities.IdGenerator",
      "photography.capabilities.Transaction"
    )
    val photoImplementations = classes.iterator.asScala
      .filter(value => value.getPackageName === "photography.usecases" && value.getName.contains("LivePhoto")).toVector
    assert(photoImplementations.nonEmpty)
    photoImplementations.foreach { implementation =>
      implementation.getConstructors.iterator.asScala.foreach { constructor =>
        val dependencies = constructor.getRawParameterTypes.iterator.asScala.filterNot(value => capabilities.contains(value.getName)).toVector
        assert(dependencies.sizeIs <= 5, s"${implementation.getName} injects ${dependencies.size} collaborators: $dependencies")
      }
    }
