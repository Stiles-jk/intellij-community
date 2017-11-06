/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.rebase

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.Executor
import com.intellij.openapi.vcs.Executor.mkdir
import com.intellij.openapi.vcs.Executor.touch
import com.intellij.vcs.test.cleanupForAssertion
import git4idea.branch.GitBranchUiHandler
import git4idea.branch.GitBranchWorker
import git4idea.branch.GitRebaseParams
import git4idea.repo.GitRepository
import git4idea.test.UNKNOWN_ERROR_TEXT
import git4idea.test.git
import git4idea.test.resolveConflicts
import org.mockito.Mockito
import kotlin.properties.Delegates

class GitMultiRepoRebaseTest : GitRebaseBaseTest() {

  private var myUltimate: GitRepository by Delegates.notNull()
  private var myCommunity: GitRepository by Delegates.notNull()
  private var myContrib: GitRepository by Delegates.notNull()
  private var myAllRepositories: List<GitRepository> by Delegates.notNull()

  override fun setUp() {
    super.setUp()

    Executor.cd(myProjectRoot)
    val community = mkdir("community")
    val contrib = mkdir("contrib")

    myUltimate = createRepository(myProjectPath)
    myCommunity = createRepository(community.path)
    myContrib = createRepository(contrib.path)
    myAllRepositories = listOf(myUltimate, myCommunity, myContrib)

    Executor.cd(myProjectRoot)
    touch(".gitignore", "community\ncontrib")
    git("add .gitignore")
    git("commit -m gitignore")
  }

  fun `test all successful`() {
    myUltimate.`place feature above master`()
    myCommunity.`diverge feature and master`()
    myContrib.`place feature on master`()

    rebase("master")

    assertSuccessfulRebaseNotification("Rebased feature on master")
    assertAllRebased()
    assertNoRebaseInProgress(myAllRepositories)
  }

  fun `test abort from critical error during rebasing 2nd root, before any commits were applied`() {
    val localChange = LocalChange(myCommunity, "new.txt", "Some content")
    `fail with critical error while rebasing 2nd root`(localChange)

    assertErrorNotification("Rebase Failed",
        """
        contrib: $UNKNOWN_ERROR_TEXT <br/>
        You can <a>retry</a> or <a>abort</a> rebase.
        $LOCAL_CHANGES_WARNING
        """)

    myCommunity.`assert feature rebased on master`()
    myContrib.`assert feature not rebased on master`()
    myUltimate.`assert feature not rebased on master`()
    assertNoRebaseInProgress(myAllRepositories)
    myUltimate.assertNoLocalChanges()

    var confirmation: String? = null
    myDialogManager.onMessage {
      confirmation = it
      Messages.YES
    }

    abortOngoingRebase()

    assertNotNull(confirmation, "Abort confirmation message was not shown")
    assertEquals("Incorrect confirmation message text",
                 cleanupForAssertion("Do you want to rollback the successful rebase in community?"),
                 cleanupForAssertion(confirmation!!))
    assertNoRebaseInProgress(myAllRepositories)
    myAllRepositories.forEach { it.`assert feature not rebased on master`() }

    localChange.verify()
  }

  fun `test abort from critical error while rebasing 2nd root, after some commits were applied`() {
    val localChange = LocalChange(myCommunity, "new.txt", "Some content")
    `fail with critical error while rebasing 2nd root after some commits are applied`(localChange)

    myVcsNotifier.lastNotification

    var confirmation: String? = null
    myDialogManager.onMessage {
      confirmation = it
      Messages.YES
    }

    abortOngoingRebase()

    assertNotNull(confirmation, "Abort confirmation message was not shown")
    assertEquals("Incorrect confirmation message text",
                 cleanupForAssertion("Do you want just to abort rebase in contrib, or also rollback the successful rebase in community?"),
                 cleanupForAssertion(confirmation!!))
    assertNoRebaseInProgress(myAllRepositories)
    myAllRepositories.forEach { it.`assert feature not rebased on master`() }

    localChange.verify()
  }

  fun `test conflicts in multiple repositories are resolved separately`() {
    myUltimate.`prepare simple conflict`()
    myCommunity.`prepare simple conflict`()
    myContrib.`diverge feature and master`()

    var facedConflictInUltimate = false
    var facedConflictInCommunity = false
    vcsHelper.onMerge({
      assertFalse(facedConflictInCommunity && facedConflictInUltimate)
      if (myUltimate.hasConflict("c.txt")) {
        assertFalse(facedConflictInUltimate)
        facedConflictInUltimate = true
        assertNoRebaseInProgress(myCommunity)
        myUltimate.resolveConflicts()
      }
      else if (myCommunity.hasConflict("c.txt")) {
        assertFalse(facedConflictInCommunity)
        facedConflictInCommunity = true
        assertNoRebaseInProgress(myUltimate)
        myCommunity.resolveConflicts()
      }
    })

    rebase("master")

    assertTrue(facedConflictInUltimate)
    assertTrue(facedConflictInCommunity)
    myAllRepositories.forEach {
      it.`assert feature rebased on master`()
      assertNoRebaseInProgress(it)
      it.assertNoLocalChanges()
    }
  }

  fun `test retry doesn't touch successful repositories`() {
    `fail with critical error while rebasing 2nd root`()

    GitRebaseUtils.continueRebase(myProject)

    assertSuccessfulRebaseNotification("Rebased feature on master")
    assertAllRebased()
    assertNoRebaseInProgress(myAllRepositories)
  }

  fun `test continue rebase shouldn't attempt to stash`() {
    myUltimate.`diverge feature and master`()
    myCommunity.`prepare simple conflict`()
    myContrib.`diverge feature and master`()

    `do nothing on merge`()
    rebase("master")
    GitRebaseUtils.continueRebase(myProject)

    `assert conflict not resolved notification`()
    assertNotRebased("feature", "master", myCommunity)
  }

  fun `test continue rebase with unresolved conflicts should show merge dialog`() {
    myUltimate.`diverge feature and master`()
    myCommunity.`prepare simple conflict`()
    myContrib.`diverge feature and master`()

    `do nothing on merge`()
    rebase("master")

    var mergeDialogShown = false
    vcsHelper.onMerge {
      mergeDialogShown = true
      myCommunity.resolveConflicts()
    }
    GitRebaseUtils.continueRebase(myProject)

    assertTrue("Merge dialog was not shown", mergeDialogShown)
    assertAllRebased()
  }

  fun `test rollback if checkout with rebase fails on 2nd root`() {
    myAllRepositories.forEach {
      it.`diverge feature and master`()
      git(it, "checkout master")
    }
    myGit.setShouldRebaseFail { it == myContrib }

    val uiHandler = Mockito.mock(GitBranchUiHandler::class.java)
    Mockito.`when`(uiHandler.progressIndicator).thenReturn(EmptyProgressIndicator())
    try {
      GitBranchWorker(myProject, myGit, uiHandler).rebaseOnCurrent(myAllRepositories, "feature")
    }
    finally {
      myGit.setShouldRebaseFail { false }
    }

    var confirmation: String? = null
    myDialogManager.onMessage {
      confirmation = it
      Messages.YES
    }

    abortOngoingRebase()

    assertNotNull(confirmation, "Abort confirmation message was not shown")
    assertEquals("Incorrect confirmation message text",
                 cleanupForAssertion("Do you want to rollback the successful rebase in community?"),
                 cleanupForAssertion(confirmation!!))
    assertNoRebaseInProgress(myAllRepositories)
    myAllRepositories.forEach {
      it.`assert feature not rebased on master`()
      assertEquals("Incorrect current branch", "master", it.currentBranchName) }
  }

  private fun `fail with critical error while rebasing 2nd root`(localChange: LocalChange? = null) {
    myAllRepositories.forEach { it.`diverge feature and master`() }
    localChange?.generate()

    myGit.setShouldRebaseFail { it == myContrib }
    try {
      rebase("master")
    }
    finally {
      myGit.setShouldRebaseFail { false }
    }
  }

  private fun `fail with critical error while rebasing 2nd root after some commits are applied`(localChange: LocalChange? = null) {
    myCommunity.`diverge feature and master`()
    myContrib.`make rebase fail on 2nd commit`()
    myUltimate.`diverge feature and master`()
    localChange?.generate()

    try {
      rebase("master")
    }
    finally {
      myGit.setShouldRebaseFail { false }
    }
  }

  private fun rebase(onto: String) {
    GitTestingRebaseProcess(myProject, GitRebaseParams(onto), myAllRepositories).rebase()
  }

  private fun abortOngoingRebase() {
    GitRebaseUtils.abort(myProject, EmptyProgressIndicator())
  }

  private fun assertAllRebased() {
    assertRebased(myUltimate, "feature", "master")
    assertRebased(myCommunity, "feature", "master")
    assertRebased(myContrib, "feature", "master")
  }
}
