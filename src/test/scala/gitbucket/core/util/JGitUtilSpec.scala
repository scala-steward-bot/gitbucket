package gitbucket.core.util

import gitbucket.core.util.GitSpecUtil.*
import gitbucket.core.util.JGitUtil.BranchInfoSimple
import org.apache.commons.io.IOUtils
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.lib.Constants
import org.scalatest.funsuite.AnyFunSuite

import scala.jdk.CollectionConverters.*

class JGitUtilSpec extends AnyFunSuite {

  test("isEmpty") {
    withTestRepository { git =>
      assert(JGitUtil.isEmpty(git))

      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")
      assert(!JGitUtil.isEmpty(git))
    }
  }

  test("getDiffs") {
    withTestRepository { git =>
      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")

      val branchId = git.getRepository.resolve("main")
      val commit = JGitUtil.getRevCommitFromId(git, branchId)

      createFile(git, Constants.HEAD, "LICENSE", "Apache License", message = "commit1")
      createFile(git, Constants.HEAD, "README.md", "body1\nbody2", message = "commit1")

      // latest commit
      val diff1 = JGitUtil.getDiffs(git, None, "main", fetchContent = false, makePatch = true)
      assert(diff1.size == 1)
      assert(diff1(0).changeType == ChangeType.MODIFY)
      assert(diff1(0).oldPath == "README.md")
      assert(diff1(0).newPath == "README.md")
      assert(!diff1(0).tooLarge)
      assert(diff1(0).patch.contains("""@@ -1 +1,2 @@
          |-body1
          |\ No newline at end of file
          |+body1
          |+body2
          |\ No newline at end of file""".stripMargin))

      // from specified commit
      val diff2 = JGitUtil.getDiffs(git, Some(commit.getName), "main", fetchContent = false, makePatch = true)
      assert(diff2.size == 2)
      assert(diff2(0).changeType == ChangeType.ADD)
      assert(diff2(0).oldPath == "/dev/null")
      assert(diff2(0).newPath == "LICENSE")
      assert(!diff2(0).tooLarge)
      assert(diff2(0).patch.contains("""+++ b/LICENSE
          |@@ -0,0 +1 @@
          |+Apache License
          |\ No newline at end of file""".stripMargin))

      assert(diff2(1).changeType == ChangeType.MODIFY)
      assert(diff2(1).oldPath == "README.md")
      assert(diff2(1).newPath == "README.md")
      assert(!diff2(1).tooLarge)
      assert(diff2(1).patch.contains("""@@ -1 +1,2 @@
          |-body1
          |\ No newline at end of file
          |+body1
          |+body2
          |\ No newline at end of file""".stripMargin))
    }
  }

  test("getRevCommitFromId") {
    withTestRepository { git =>
      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")

      // branch name
      val branchId = git.getRepository.resolve("main")
      val commit1 = JGitUtil.getRevCommitFromId(git, branchId)

      // commit id
      val commitName = commit1.getName
      val commitId = git.getRepository.resolve(commitName)
      val commit2 = JGitUtil.getRevCommitFromId(git, commitId)

      // tag name
      JGitUtil.createTag(git, "1.0", None, commitName)
      val tagId = git.getRepository.resolve("1.0")
      val commit3 = JGitUtil.getRevCommitFromId(git, tagId)

      // all refer same commit
      assert(commit1 == commit2)
      assert(commit1 == commit3)
      assert(commit2 == commit3)
    }
  }

  test("getCommitCount and getAllCommitIds") {
    withTestRepository { git =>
      // getCommitCount
      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")
      assert(JGitUtil.getCommitCount(git, "main") == 1)

      createFile(git, Constants.HEAD, "README.md", "body2", message = "commit2")
      assert(JGitUtil.getCommitCount(git, "main") == 2)

      // maximum limit
      (3 to 10).foreach { i =>
        createFile(git, Constants.HEAD, "README.md", "body" + i, message = "commit" + i)
      }
      assert(JGitUtil.getCommitCount(git, "main", 5) == 5)

      // actual commit count
      val gitLog = git.log.add(git.getRepository.resolve("main")).all
      assert(gitLog.call.asScala.toSeq.size == 10)

      // getAllCommitIds
      val allCommits = JGitUtil.getAllCommitIds(git)
      assert(allCommits.size == 10)
    }
  }

  test("getCommitLog") {
    withTestRepository { git =>
      /** repo looks like this
       * commit1 -> commit2 -> commit3 [main]
       * \-> commit4 [branch1]
       * */
      val root = git.getRepository.resolve("main")

      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")
      val commit1 = git.getRepository.resolve("main")

      createFile(git, Constants.HEAD, "LICENSE", "Apache License", message = "commit2")
      val commit2 = git.getRepository.resolve("main")
      // also make a tag
      JGitUtil.createTag(git, "t1", None, commit2.getName)

      createFile(git, Constants.HEAD, "README.md", "body1\nbody2", message = "commit3")
      val commit3 = git.getRepository.resolve("main")

      // create branch
      JGitUtil.createBranch(git, "main", "branch1")
      createFile(git, "branch1", "README.md", "body2", message = "commit4")
      val commit4 = git.getRepository.resolve("branch1")

      // compare results for empty → commit3
      assert(
        JGitUtil.getCommitLogs(git, commit3.getName, includesLastCommit = true)(_ => false) == JGitUtil.getCommitLog(
          git,
          root,
          commit3
        )
      )
      // compare results for commit1 → commit3
      assert(
        JGitUtil.getCommitLogs(git, commit3.getName, includesLastCommit = true)(
          _.getName != commit3.getName
        ) == JGitUtil.getCommitLog(git, commit1, commit3)
      )

      // compare results for empty → commit4
      assert(
        JGitUtil.getCommitLogs(git, commit4.getName, includesLastCommit = true)(_ => false) == JGitUtil.getCommitLog(
          git,
          root,
          commit4
        )
      )

      // check with names
      assert(JGitUtil.getCommitLog(git, "main", "branch1").size == 1)

      // tag names must work, too
      assertResult(JGitUtil.getCommitLog(git, "t1", "main").length)(1)
    }
  }

  test("createBranch, branchesOfCommit and getBranches") {
    withTestRepository { git =>
      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")

      // createBranch
      assert(JGitUtil.createBranch(git, "main", "test1") == Right("Branch created."))
      assert(JGitUtil.createBranch(git, "main", "test2") == Right("Branch created."))
      assert(JGitUtil.createBranch(git, "main", "test2") == Left("Sorry, that branch already exists."))

      // verify
      val branches = git.branchList.call()
      assert(branches.size == 3)
      assert(branches.get(0).getName == "refs/heads/main")
      assert(branches.get(1).getName == "refs/heads/test1")
      assert(branches.get(2).getName == "refs/heads/test2")

      // getBranchesOfCommit
      val commit = JGitUtil.getRevCommitFromId(git, git.getRepository.resolve("main"))
      val branchesOfCommit = JGitUtil.getBranchesOfCommit(git, commit.getName)
      assert(branchesOfCommit.size == 3)
      assert(branchesOfCommit(0) == "main")
      assert(branchesOfCommit(1) == "test1")
      assert(branchesOfCommit(2) == "test2")
    }
  }

  test("getBranches") {
    withTestRepository { git =>
      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")
      JGitUtil.createBranch(git, "main", "test1")

      createFile(git, Constants.HEAD, "README.md", "body2", message = "commit2")
      JGitUtil.createBranch(git, "main", "test2")

      // getBranches
      val branches = JGitUtil.getBranches(git, "main", origin = true)
      assert(branches.size == 3)

      assert(branches(0).name == "main")
      assert(branches(0).committerName == "dummy")
      assert(branches(0).committerEmailAddress == "dummy@example.com")

      assert(branches(1).name == "test1")
      assert(branches(1).committerName == "dummy")
      assert(branches(1).committerEmailAddress == "dummy@example.com")

      assert(branches(2).name == "test2")
      assert(branches(2).committerName == "dummy")
      assert(branches(2).committerEmailAddress == "dummy@example.com")

      assert(branches(0).commitId != branches(1).commitId)
      assert(branches(0).commitId == branches(2).commitId)
    }
  }

  test("getBranchesNoMergeInfo") {
    withTestRepository { git =>
      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")
      JGitUtil.createBranch(git, "main", "test1")

      createFile(git, Constants.HEAD, "README.md", "body2", message = "commit2")
      JGitUtil.createBranch(git, "main", "test2")

      // getBranches
      val branchesNMI = JGitUtil.getBranchesNoMergeInfo(git)
      val branches = JGitUtil.getBranches(git, "main", origin = true)

      assert(
        branches.map(bi =>
          BranchInfoSimple(bi.name, bi.committerName, bi.commitTime, bi.committerEmailAddress, bi.commitId)
        ) == branchesNMI
      )
    }
  }

  test("createTag, getTagsOfCommit and getTagsOnCommit") {
    withTestRepository { git =>
      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")

      // createTag
      assert(JGitUtil.createTag(git, "1.0", Some("test1"), "main") == Right("Tag added."))
      assert(
        JGitUtil.createTag(git, "1.0", Some("test2"), "main") == Left("Sorry, some Git operation error occurs.")
      )

      // record current commit
      val commit = JGitUtil.getRevCommitFromId(git, git.getRepository.resolve("main"))

      // createTag
      createFile(git, Constants.HEAD, "LICENSE", "Apache License", message = "commit2")
      assert(JGitUtil.createTag(git, "1.1", Some("test3"), "main") == Right("Tag added."))

      // verify
      val allTags = git.tagList().call().asScala
      assert(allTags.size == 2)
      assert(allTags(0).getName == "refs/tags/1.0")
      assert(allTags(1).getName == "refs/tags/1.1")

      // getTagsOfCommit
      val tagsOfCommit = JGitUtil.getTagsOfCommit(git, commit.getName)
      assert(tagsOfCommit.size == 2)
      assert(tagsOfCommit(0) == "1.1")
      assert(tagsOfCommit(1) == "1.0")

      // getTagsOnCommit
      val tagsOnCommit = JGitUtil.getTagsOnCommit(git, "main")
      assert(tagsOnCommit.size == 1)
      assert(tagsOnCommit(0) == "1.1")
    }
  }

  test("openFile for non-LFS file") {
    withTestRepository { git =>
      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")
      createFile(git, Constants.HEAD, "LICENSE", "Apache License", message = "commit2")

      val objectId = git.getRepository.resolve("main")
      val commit = JGitUtil.getRevCommitFromId(git, objectId)

      // Since Non-LFS file doesn't need RepositoryInfo give null
      assert(JGitUtil.openFile(git, null, commit.getTree, "README.md") { in =>
        IOUtils.toString(in, "UTF-8")
      } == "body1")

      assert(JGitUtil.openFile(git, null, commit.getTree, "LICENSE") { in =>
        IOUtils.toString(in, "UTF-8")
      } == "Apache License")
    }
  }

  test("getContentFromPath") {
    withTestRepository { git =>
      createFile(git, Constants.HEAD, "README.md", "body1", message = "commit1")
      createFile(git, Constants.HEAD, "LARGE_FILE", "body1" * 1000000, message = "commit1")

      val objectId = git.getRepository.resolve("main")
      val commit = JGitUtil.getRevCommitFromId(git, objectId)

      val content1 = JGitUtil.getContentFromPath(git, commit.getTree, "README.md", fetchLargeFile = true)
      assert(content1.map(x => new String(x, "UTF-8")).contains("body1"))

      val content2 = JGitUtil.getContentFromPath(git, commit.getTree, "LARGE_FILE", fetchLargeFile = false)
      assert(content2.isEmpty)

      val content3 = JGitUtil.getContentFromPath(git, commit.getTree, "LARGE_FILE", fetchLargeFile = true)
      assert(content3.map(x => new String(x, "UTF-8")).contains("body1" * 1000000))
    }
  }

  test("getBlame") {
    withTestRepository { git =>
      createFile(git, Constants.HEAD, "README.md", "body1\nbody2\nbody3", message = "commit1")
      createFile(git, Constants.HEAD, "README.md", "body0\nbody2\nbody3", message = "commit2")

      val blames = JGitUtil.getBlame(git, "main", "README.md").toSeq

      assert(blames.size == 2)
      assert(blames(0).message == "commit2")
      assert(blames(0).lines == Set(0))
      assert(blames(1).message == "commit1")
      assert(blames(1).lines == Set(1, 2))
    }
  }

  test("getFileList(git: Git, revision: String, path)") {
    withTestRepository { git =>
      def list(branch: String, path: String) =
        JGitUtil.getFileList(git, branch, path).map(finfo => (finfo.name, finfo.message, finfo.isDirectory))
      assert(list("main", ".") == Nil)
      assert(list("main", "dir/subdir") == Nil)
      assert(list("branch", ".") == Nil)
      assert(list("branch", "dir/subdir") == Nil)

      createFile(git, "main", "README.md", "body1", message = "commit1")

      assert(list("main", ".") == List(("README.md", "commit1", false)))
      assert(list("main", "dir/subdir") == Nil)
      assert(list("branch", ".") == Nil)
      assert(list("branch", "dir/subdir") == Nil)

      createFile(git, "main", "README.md", "body2", message = "commit2")

      assert(list("main", ".") == List(("README.md", "commit2", false)))
      assert(list("main", "dir/subdir") == Nil)
      assert(list("branch", ".") == Nil)
      assert(list("branch", "dir/subdir") == Nil)

      createFile(git, "main", "dir/subdir/File3.md", "body3", message = "commit3")

      assert(list("main", ".") == List(("dir/subdir", "commit3", true), ("README.md", "commit2", false)))
      assert(list("main", "dir/subdir") == List(("File3.md", "commit3", false)))
      assert(list("branch", ".") == Nil)
      assert(list("branch", "dir/subdir") == Nil)

      createFile(git, "main", "dir/subdir/File4.md", "body4", message = "commit4")

      assert(list("main", ".") == List(("dir/subdir", "commit4", true), ("README.md", "commit2", false)))
      assert(list("main", "dir/subdir") == List(("File3.md", "commit3", false), ("File4.md", "commit4", false)))
      assert(list("branch", ".") == Nil)
      assert(list("branch", "dir/subdir") == Nil)

      createFile(git, "main", "README5.md", "body5", message = "commit5")

      assert(
        list("main", ".") == List(
          ("dir/subdir", "commit4", true),
          ("README.md", "commit2", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("main", "dir/subdir") == List(("File3.md", "commit3", false), ("File4.md", "commit4", false)))
      assert(list("branch", ".") == Nil)
      assert(list("branch", "dir/subdir") == Nil)

      createFile(git, "main", "README.md", "body6", message = "commit6")

      assert(
        list("main", ".") == List(
          ("dir/subdir", "commit4", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("main", "dir/subdir") == List(("File3.md", "commit3", false), ("File4.md", "commit4", false)))
      assert(list("branch", ".") == Nil)
      assert(list("branch", "dir/subdir") == Nil)

      git.branchCreate().setName("branch").setStartPoint("main").call()

      assert(
        list("main", ".") == List(
          ("dir/subdir", "commit4", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("main", "dir/subdir") == List(("File3.md", "commit3", false), ("File4.md", "commit4", false)))
      assert(
        list("branch", ".") == List(
          ("dir/subdir", "commit4", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("branch", "dir/subdir") == List(("File3.md", "commit3", false), ("File4.md", "commit4", false)))

      createFile(git, "branch", "dir/subdir/File3.md", "body7", message = "commit7")

      assert(
        list("main", ".") == List(
          ("dir/subdir", "commit4", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("main", "dir/subdir") == List(("File3.md", "commit3", false), ("File4.md", "commit4", false)))
      assert(
        list("branch", ".") == List(
          ("dir/subdir", "commit7", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("branch", "dir/subdir") == List(("File3.md", "commit7", false), ("File4.md", "commit4", false)))

      createFile(git, "main", "dir8/File8.md", "body8", message = "commit8")

      assert(
        list("main", ".") == List(
          ("dir/subdir", "commit4", true),
          ("dir8", "commit8", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("main", "dir/subdir") == List(("File3.md", "commit3", false), ("File4.md", "commit4", false)))
      assert(
        list("branch", ".") == List(
          ("dir/subdir", "commit7", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("branch", "dir/subdir") == List(("File3.md", "commit7", false), ("File4.md", "commit4", false)))

      createFile(git, "branch", "dir/subdir9/File9.md", "body9", message = "commit9")

      assert(
        list("main", ".") == List(
          ("dir/subdir", "commit4", true),
          ("dir8", "commit8", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("main", "dir/subdir") == List(("File3.md", "commit3", false), ("File4.md", "commit4", false)))
      assert(
        list("branch", ".") == List(
          ("dir", "commit9", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("branch", "dir/subdir") == List(("File3.md", "commit7", false), ("File4.md", "commit4", false)))

      mergeAndCommit(git, "main", "branch", message = "merge10")

      assert(
        list("main", ".") == List(
          ("dir", "commit9", true),
          ("dir8", "commit8", true),
          ("README.md", "commit6", false),
          ("README5.md", "commit5", false)
        )
      )
      assert(list("main", "dir/subdir") == List(("File3.md", "commit7", false), ("File4.md", "commit4", false)))
    }
  }

  test("getFileList subfolder multi-origin (issue #721)") {
    withTestRepository { git =>
      def list(branch: String, path: String) =
        JGitUtil.getFileList(git, branch, path).map(finfo => (finfo.name, finfo.message, finfo.isDirectory))
      createFile(git, "main", "README.md", "body1", message = "commit1")
      createFile(git, "branch", "test/text2.txt", "body2", message = "commit2")
      mergeAndCommit(git, "main", "branch", message = "merge3")
      assert(list("main", "test") == List(("text2.txt", "commit2", false)))
    }
  }

  test("getLfsObjects") {
    val str = """version https://git-lfs.github.com/spec/v1
                |oid sha256:aa8a7a4903572ccd1571c03f442661a983d79b53bbb7bcdd50769429f0b24ab8
                |size 178643""".stripMargin

    val attrs = JGitUtil.getLfsObjects(str)
    assert(attrs("oid") == "sha256:aa8a7a4903572ccd1571c03f442661a983d79b53bbb7bcdd50769429f0b24ab8")
    assert(attrs("size") == "178643")
  }

  test("getContentInfo") {
    // TODO
  }

}
