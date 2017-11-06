package forge

import java.io.FileOutputStream
import java.util.jar.{JarEntry, JarOutputStream}

import ammonite.ops._
import forge.define.Target
import forge.discover.Discovered
import forge.eval.{Evaluator, PathRef}
import forge.util.{Args, OSet}
import utest._

object JavaCompileJarTests extends TestSuite{
  def compileAll(sources: Target[Seq[PathRef]])  = {
    new Target.Subprocess(
      Seq(sources),
      args =>
        Seq("javac") ++
          args[Seq[PathRef]](0).map(_.path.toString) ++
          Seq("-d", args.dest.toString)
    ).map(_.dest)
  }


  private def createManifest(mainClass: Option[String]) = {
    val m = new java.util.jar.Manifest()
    m.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    m.getMainAttributes.putValue( "Created-By", "Scala Forge" )
    mainClass.foreach(
      m.getMainAttributes.put(java.util.jar.Attributes.Name.MAIN_CLASS, _)
    )
    m
  }

  def createJar(outputPath: Path, inputPaths: Seq[Path], mainClass: Option[String] = None ): Option[Path] = {
    rm(outputPath)
    if(inputPaths.isEmpty) None
    else {
      mkdir(outputPath/up)

      val jar = new JarOutputStream(
        new FileOutputStream(outputPath.toIO),
        createManifest(mainClass)
      )

      try{
        assert(inputPaths.forall(exists(_)))
        for{
          p <- inputPaths
          (file, mapping) <-
            if (p.isFile) Iterator(p -> empty/p.last)
            else ls.rec(p).filter(_.isFile).map(sub => sub -> sub.relativeTo(p))
        } {
          val entry = new JarEntry(mapping.toString)
          entry.setTime(file.mtime.toMillis)
          jar.putNextEntry(entry)
          jar.write(read.bytes(file))
          jar.closeEntry
        }
      } finally {
        jar.close
      }

      Some(outputPath)
    }
  }
  case class jarUp(roots: Target[PathRef]*) extends Target[PathRef]{

    val inputs = roots
    def evaluate(args: Args): PathRef = {
      createJar(args.dest, args.args.map(_.asInstanceOf[PathRef].path))
      PathRef(args.dest)
    }
  }

  val tests = Tests{
    'javac {
      val workspacePath = pwd / 'target / 'workspace / 'javac
      val javacSrcPath = pwd / 'core / 'src / 'test / 'examples / 'javac
      val javacDestPath = workspacePath / 'src

      mkdir(pwd / 'target / 'workspace / 'javac)
      cp(javacSrcPath, javacDestPath)

      object Build extends Target.Cacher{
        def sourceRootPath = javacDestPath / 'src
        def resourceRootPath = javacDestPath / 'resources

        // sourceRoot -> allSources -> classFiles
        //                                |
        //                                v
        //           resourceRoot ---->  jar
        def sourceRoot = T{ Target.path(sourceRootPath) }
        def resourceRoot = T{ Target.path(resourceRootPath) }
        def allSources = T{ ls.rec(sourceRoot().path).map(PathRef(_)) }
        def classFiles = T{ compileAll(allSources) }
        def jar = T{ jarUp(resourceRoot, classFiles) }
      }
      import Build._
      val mapping = Discovered.mapping(Build)

      def check(targets: OSet[Target[_]], expected: OSet[Target[_]]) = {
        val evaluator = new Evaluator(workspacePath, mapping)

        val evaluated = evaluator.evaluate(targets).evaluated.filter(mapping.contains)
        assert(evaluated == expected)
      }

      def append(path: Path, txt: String) = ammonite.ops.write.append(path, txt)


      check(
        targets = OSet(jar),
        expected = OSet(resourceRoot, sourceRoot, allSources, classFiles, jar)
      )

      // Re-running with no changes results in nothing being evaluated
      check(targets = OSet(jar), expected = OSet())

      // Appending an empty string gets ignored due to file-content hashing
      append(sourceRootPath / "Foo.java", "")
      check(targets = OSet(jar), expected = OSet())

      // Appending whitespace forces a recompile, but the classfilesend up
      // exactly the same so no re-jarring.
      append(sourceRootPath / "Foo.java", " ")
      check(targets = OSet(jar), expected = OSet(sourceRoot, allSources, classFiles))

      // Appending a new class changes the classfiles, which forces us to
      // re-create the final jar
      append(sourceRootPath / "Foo.java", "\nclass FooTwo{}")
      check(targets = OSet(jar), expected = OSet(sourceRoot, allSources, classFiles, jar))

      // Tweaking the resources forces rebuild of the final jar, without
      // recompiling classfiles
      append(resourceRootPath / "hello.txt", " ")
      check(targets = OSet(jar), expected = OSet(resourceRoot, jar))

      // Asking for an intermediate target forces things to be build up to that
      // target only; these are re-used for any downstream targets requested
      append(sourceRootPath / "Bar.java", "\nclass BarTwo{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = OSet(classFiles), expected = OSet(sourceRoot, allSources, classFiles))
      check(targets = OSet(jar), expected = OSet(resourceRoot, jar))
      check(targets = OSet(allSources), expected = OSet())

      append(sourceRootPath / "Bar.java", "\nclass BarThree{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = OSet(resourceRoot), expected = OSet(resourceRoot))
      check(targets = OSet(allSources), expected = OSet(sourceRoot, allSources))
      check(targets = OSet(jar), expected = OSet(classFiles, jar))

      val jarContents = %%('jar, "-tf", workspacePath/'jar)(workspacePath).out.string
      val expectedJarContents =
        """META-INF/MANIFEST.MF
          |hello.txt
          |test/Bar.class
          |test/BarThree.class
          |test/BarTwo.class
          |test/Foo.class
          |test/FooTwo.class
          |""".stripMargin
      assert(jarContents == expectedJarContents)

      val executed = %%('java, "-cp", workspacePath/'jar, "test.Foo")(workspacePath).out.string
      assert(executed == (31337 + 271828) + "\n")
    }
  }
}
