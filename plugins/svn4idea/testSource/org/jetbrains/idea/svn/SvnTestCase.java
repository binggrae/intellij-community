// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.svn;

import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.startup.impl.StartupManagerImpl;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsConfiguration;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsShowConfirmationOption;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl;
import com.intellij.openapi.vcs.rollback.RollbackProgressListener;
import com.intellij.openapi.vcs.update.CommonUpdateProjectAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.ApplicationRule;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.vcs.AbstractJunitVcsTestCase;
import com.intellij.testFramework.vcs.MockChangeListManagerGate;
import com.intellij.testFramework.vcs.MockChangelistBuilder;
import com.intellij.testFramework.vcs.TestClientRunner;
import com.intellij.util.Processor;
import com.intellij.util.ThrowableRunnable;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.io.ZipUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.actions.CreateExternalAction;
import org.jetbrains.idea.svn.api.Url;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.ExternalResource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext;
import static com.intellij.openapi.application.PluginPathManager.getPluginHomePath;
import static com.intellij.openapi.util.io.FileUtil.*;
import static com.intellij.openapi.util.text.StringUtil.isEmptyOrSpaces;
import static com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile;
import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.testFramework.UsefulTestCase.*;
import static com.intellij.util.FunctionUtil.nullConstant;
import static com.intellij.util.ObjectUtils.notNull;
import static com.intellij.util.containers.ContainerUtil.map2Array;
import static com.intellij.util.lang.CompoundRuntimeException.throwIfNotEmpty;
import static java.util.Collections.singletonMap;
import static org.jetbrains.idea.svn.SvnUtil.parseUrl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public abstract class SvnTestCase extends AbstractJunitVcsTestCase {
  @ClassRule public static final ApplicationRule appRule = new ApplicationRule();
  @ClassRule public static final ExternalResource ideaTempDirectoryRule = new ExternalResource() {
    @Override
    protected void before() throws Throwable {
      ensureExists(new File(PathManager.getTempPath()));
    }
  };

  private static final String ORIGINAL_TEMP_DIRECTORY = getTempDirectory();

  protected TempDirTestFixture myTempDirFixture;
  protected Url myRepositoryUrl;
  protected String myRepoUrl;
  protected TestClientRunner myRunner;
  protected String myWcRootName;

  private final String myTestDataDir;
  private File myRepoRoot;
  private File myWcRoot;
  private ChangeListManagerGate myGate;
  protected String myAnotherRepoUrl;
  protected File myPluginRoot;

  protected ProjectLevelVcsManagerImpl vcsManager;
  protected ChangeListManagerImpl changeListManager;
  protected VcsDirtyScopeManager dirtyScopeManager;
  protected SvnVcs vcs;

  protected SvnTestCase() {
    this("testData");
  }

  protected SvnTestCase(@NotNull String testDataDir) {
    myTestDataDir = testDataDir;
    myWcRootName = "wcroot";
  }

  @NotNull
  public static String getPluginHome() {
    return getPluginHomePath("svn4idea");
  }

  @BeforeClass
  public static void assumeWindowsUnderTeamCity() {
    if (IS_UNDER_TEAMCITY) {
      assumeTrue("Windows is required", SystemInfo.isWindows);
    }
  }

  @Before
  public void setUp() throws Exception {
    runInEdtAndWait(() -> {
      myTempDirFixture = IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture();
      myTempDirFixture.setUp();
      resetCanonicalTempPathCache(myTempDirFixture.getTempDirPath());

      myPluginRoot = new File(getPluginHome());
      myClientBinaryPath = getSvnClientDirectory();
      myRunner =
        SystemInfo.isMac ? createClientRunner(singletonMap("DYLD_LIBRARY_PATH", myClientBinaryPath.getPath())) : createClientRunner();

      myRepoRoot = virtualToIoFile(myTempDirFixture.findOrCreateDir("svnroot"));
      ZipUtil.extract(new File(myPluginRoot, getTestDataDir() + "/svn/newrepo.zip"), myRepoRoot, null);

      myWcRoot = virtualToIoFile(myTempDirFixture.findOrCreateDir(myWcRootName));
      myRepoUrl = (SystemInfo.isWindows ? "file:///" : "file://") + toSystemIndependentName(myRepoRoot.getPath());
      myRepositoryUrl = parseUrl(myRepoUrl);

      verify(runSvn("co", myRepoUrl, myWcRoot.getPath()));

      initProject(myWcRoot, this.getTestName());
      activateVCS(SvnVcs.VCS_NAME);

      vcsManager = (ProjectLevelVcsManagerImpl)ProjectLevelVcsManager.getInstance(myProject);
      changeListManager = ChangeListManagerImpl.getInstanceImpl(myProject);
      dirtyScopeManager = VcsDirtyScopeManager.getInstance(myProject);
      vcs = SvnVcs.getInstance(myProject);
      myGate = new MockChangeListManagerGate(changeListManager);

      ((StartupManagerImpl)StartupManager.getInstance(myProject)).runPostStartupActivities();
      refreshSvnMappingsSynchronously();
    });

    refreshChanges();
  }

  @NotNull
  private File getSvnClientDirectory() {
    File svnBinDir = new File(myPluginRoot, getTestDataDir() + "/svn/bin");
    String executablePath =
      SystemInfo.isWindows ? "windows/svn.exe" : SystemInfo.isLinux ? "linux/svn" : SystemInfo.isMac ? "mac/svn" : null;
    assertNotNull("No Subversion executable was found " + SystemInfo.OS_NAME, executablePath);

    File svnExecutable = new File(svnBinDir, executablePath);
    assertTrue(svnExecutable + " is not executable", svnExecutable.canExecute());

    return svnExecutable.getParentFile();
  }

  protected void refreshSvnMappingsSynchronously() {
    final Semaphore semaphore = new Semaphore();
    semaphore.down();
    ((SvnFileUrlMappingImpl) vcs.getSvnFileUrlMapping()).realRefresh(() -> semaphore.up());
    semaphore.waitFor();
  }

  protected void refreshChanges() {
    dirtyScopeManager.markEverythingDirty();
    changeListManager.ensureUpToDate(false);
  }

  protected void waitChangesAndAnnotations() {
    changeListManager.ensureUpToDate(false);
    ((VcsAnnotationLocalChangesListenerImpl)vcsManager.getAnnotationLocalChangesListener()).calmDown();
  }

  @NotNull
  protected Set<String> commit(@NotNull List<Change> changes, @NotNull String message) {
    Set<String> feedback = new HashSet<>();
    //noinspection unchecked
    throwIfNotEmpty((List)vcs.getCheckinEnvironment().commit(changes, message, nullConstant(), feedback));
    return feedback;
  }

  protected void rollback(@NotNull List<Change> changes) {
    List<VcsException> exceptions = new ArrayList<>();
    vcs.createRollbackEnvironment().rollbackChanges(changes, exceptions, RollbackProgressListener.EMPTY);
    //noinspection unchecked
    throwIfNotEmpty((List)exceptions);
  }

  @Override
  protected void projectCreated() {
    SvnApplicationSettings.getInstance().setCommandLinePath(myClientBinaryPath + File.separator + "svn");
  }

  @After
  public void tearDown() throws Exception {
    runInEdtAndWait(() -> {
      tearDownProject();

      if (myTempDirFixture != null) {
        myTempDirFixture.tearDown();
        myTempDirFixture = null;
      }
      resetCanonicalTempPathCache(ORIGINAL_TEMP_DIRECTORY);
    });
  }

  protected ProcessOutput runSvn(String... commandLine) throws IOException {
    return myRunner.runClient("svn", null, myWcRoot, commandLine);
  }

  protected void enableSilentOperation(final VcsConfiguration.StandardConfirmation op) {
    setStandardConfirmation(SvnVcs.VCS_NAME, op, VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY);
  }

  protected void disableSilentOperation(final VcsConfiguration.StandardConfirmation op) {
    setStandardConfirmation(SvnVcs.VCS_NAME, op, VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY);
  }

  protected void checkin() throws IOException {
    runInAndVerifyIgnoreOutput("ci", "-m", "test");
  }

  protected void update() throws IOException {
    runInAndVerifyIgnoreOutput("up");
  }

  protected List<Change> getChangesInScope(final VcsDirtyScope dirtyScope) throws VcsException {
    MockChangelistBuilder builder = new MockChangelistBuilder();
    vcs.getChangeProvider().getChanges(dirtyScope, builder, new EmptyProgressIndicator(), myGate);
    return builder.getChanges();
  }

  protected void undo() {
    runInEdtAndWait(() -> {
      final TestDialog oldTestDialog = Messages.setTestDialog(TestDialog.OK);
      try {
        UndoManager.getInstance(myProject).undo(null);
      }
      finally {
        Messages.setTestDialog(oldTestDialog);
      }
    });
  }

  protected void prepareInnerCopy(final boolean anotherRepository) throws Exception {
    if (anotherRepository) {
      createAnotherRepo();
    }

    final String mainUrl = myRepoUrl + "/root/source";
    final String externalURL = (anotherRepository ? myAnotherRepoUrl : myRepoUrl) + "/root/target";
    final SubTree subTree = new SubTree(myWorkingCopyDir);
    checkin();

    withDisabledChangeListManager(() -> {
      final File rootFile = virtualToIoFile(subTree.myRootDir);
      delete(rootFile);
      delete(new File(myWorkingCopyDir.getPath() + File.separator + ".svn"));
      assertDoesntExist(rootFile);
      refreshVfs();

      runInAndVerifyIgnoreOutput("co", mainUrl);
      final File sourceDir = new File(myWorkingCopyDir.getPath(), "source");
      final File innerDir = new File(sourceDir, "inner1/inner2/inner");
      runInAndVerifyIgnoreOutput("co", externalURL, innerDir.getPath());
      refreshVfs();
    });
  }

  public String getTestDataDir() {
    return myTestDataDir;
  }

  protected class SubTree {
    public final VirtualFile myBase;
    public VirtualFile myRootDir;
    public VirtualFile mySourceDir;
    public VirtualFile myTargetDir;

    public VirtualFile myS1File;
    public VirtualFile myS2File;

    public final List<VirtualFile> myTargetFiles = new ArrayList<>();
    public static final String ourS1Contents = "123";
    public static final String ourS2Contents = "abc";

    private VirtualFile findChild(final VirtualFile parent, final String name, final String content, boolean create) {
      final VirtualFile result = parent.findChild(name);
      if (result != null || !create) return result;
      return content == null ? createDirInCommand(parent, name) : createFileInCommand(parent, name, content);
    }

    public SubTree(@NotNull VirtualFile base) {
      myBase = base;
      refresh(true);
    }

    public void refresh(boolean create) {
      myRootDir = findChild(myBase, "root", null, create);
      mySourceDir = findChild(myRootDir, "source", null, create);
      myS1File = findChild(mySourceDir, "s1.txt", ourS1Contents, create);
      myS2File = findChild(mySourceDir, "s2.txt", ourS2Contents, create);

      myTargetDir = findChild(myRootDir, "target", null, create);
      myTargetFiles.clear();
      for (int i = 0; i < 3; i++) {
        myTargetFiles.add(findChild(myTargetDir, "t" + (i + 10) + ".txt", ourS1Contents, create));
      }
    }
  }

  public String prepareBranchesStructure() throws Exception {
    final String mainUrl = myRepoUrl + "/trunk";
    runInAndVerifyIgnoreOutput("mkdir", "-m", "mkdir", mainUrl);
    runInAndVerifyIgnoreOutput("mkdir", "-m", "mkdir", myRepoUrl + "/branches");
    runInAndVerifyIgnoreOutput("mkdir", "-m", "mkdir", myRepoUrl + "/tags");

    String branchUrl = myRepoUrl + "/branches/b1";

    withDisabledChangeListManager(() -> {
      assertTrue(delete(new File(myWorkingCopyDir.getPath() + File.separator + ".svn")));
      refreshVfs();

      runInAndVerifyIgnoreOutput("co", mainUrl, myWorkingCopyDir.getPath());
      enableSilentOperation(VcsConfiguration.StandardConfirmation.ADD);
      new SubTree(myWorkingCopyDir);
      checkin();
      runInAndVerifyIgnoreOutput("copy", "-q", "-m", "coppy", mainUrl, branchUrl);
    });

    return branchUrl;
  }

  public void prepareExternal() throws Exception {
    prepareExternal(true, true, false);
  }

  public void prepareExternal(boolean commitExternalDefinition, boolean updateExternal, boolean anotherRepository) throws Exception {
    if (anotherRepository) {
      createAnotherRepo();
    }

    final String mainUrl = myRepoUrl + "/root/source";
    final String externalURL = (anotherRepository ? myAnotherRepoUrl : myRepoUrl) + "/root/target";
    final SubTree subTree = new SubTree(myWorkingCopyDir);
    checkin();

    withDisabledChangeListManager(() -> {
      final File rootFile = virtualToIoFile(subTree.myRootDir);
      delete(rootFile);
      delete(new File(myWorkingCopyDir.getPath() + File.separator + ".svn"));
      assertDoesntExist(rootFile);
      refreshVfs();

      final File sourceDir = new File(myWorkingCopyDir.getPath(), "source");
      runInAndVerifyIgnoreOutput("co", mainUrl, sourceDir.getPath());
      CreateExternalAction.addToExternalProperty(vcs, sourceDir, "external", externalURL);

      if (updateExternal) {
        runInAndVerifyIgnoreOutput("up", sourceDir.getPath());
      }
      if (commitExternalDefinition) {
        runInAndVerifyIgnoreOutput("ci", "-m", "test", sourceDir.getPath());
      }

      if (updateExternal) {
        refreshVfs();
        assertExists(new File(sourceDir, "external"));
      }
    });
  }

  protected void withDisabledChangeListManager(@NotNull ThrowableRunnable<? extends Exception> action) throws Exception {
    changeListManager.stopEveryThingIfInTestMode();
    action.run();
    changeListManager.forceGoInTestMode();
    refreshSvnMappingsSynchronously();
  }

  protected void createAnotherRepo() throws Exception {
    File repo = virtualToIoFile(myTempDirFixture.findOrCreateDir("anotherRepo"));
    copyDir(myRepoRoot, repo);
    myAnotherRepoUrl = (SystemInfo.isWindows ? "file:///" : "file://") + toSystemIndependentName(repo.getPath());
    VirtualFile tmpWcVf = myTempDirFixture.findOrCreateDir("anotherRepoWc");
    File tmpWc = virtualToIoFile(tmpWcVf);
    runInAndVerifyIgnoreOutput("co", myAnotherRepoUrl, tmpWc.getPath());
    new SubTree(tmpWcVf);
    runInAndVerifyIgnoreOutput(tmpWc, "add", "root");
    runInAndVerifyIgnoreOutput(tmpWc, "ci", "-m", "fff");
    delete(tmpWc);
  }

  protected void imitUpdate() {
    vcsManager.getOptions(VcsConfiguration.StandardOption.UPDATE).setValue(false);
    final CommonUpdateProjectAction action = new CommonUpdateProjectAction();
    action.getTemplatePresentation().setText("1");
    action
      .actionPerformed(new AnActionEvent(null, getProjectContext(myProject), "test", new Presentation(), ActionManager.getInstance(), 0));

    waitChangesAndAnnotations();
  }

  protected void runAndVerifyStatusSorted(final String... stdoutLines) throws IOException {
    runStatusAcrossLocks(myWcRoot, true, map2Array(stdoutLines, String.class, it -> toSystemDependentName(it)));
  }

  protected void runAndVerifyStatus(final String... stdoutLines) throws IOException {
    runStatusAcrossLocks(myWcRoot, false, map2Array(stdoutLines, String.class, it -> toSystemDependentName(it)));
  }

  private void runStatusAcrossLocks(@Nullable File workingDir, final boolean sorted, final String... stdoutLines) throws IOException {
    final Processor<ProcessOutput> primitiveVerifier = output -> {
      if (sorted) {
        verifySorted(output, stdoutLines);
      } else {
        verify(output, stdoutLines);
      }
      return false;
    };
    runAndVerifyAcrossLocks(workingDir, new String[]{"status"}, output -> {
      final List<String> lines = output.getStdoutLines();
      for (String line : lines) {
        if (line.trim().startsWith("L")) {
          return true; // i.e. continue tries
        }
      }
      primitiveVerifier.process(output);
      return false;
    }, primitiveVerifier);
  }

  protected void runInAndVerifyIgnoreOutput(final String... inLines) throws IOException {
    final Processor<ProcessOutput> verifier = createPrimitiveExitCodeVerifier();
    runAndVerifyAcrossLocks(myWcRoot, myRunner, inLines, verifier, verifier);
  }

  private static Processor<ProcessOutput> createPrimitiveExitCodeVerifier() {
    return output -> {
      assertEquals(output.getStderr(), 0, output.getExitCode());
      return false;
    };
  }

  public static void runInAndVerifyIgnoreOutput(File workingDir, final TestClientRunner runner, final String[] input) throws IOException {
    final Processor<ProcessOutput> verifier = createPrimitiveExitCodeVerifier();
    runAndVerifyAcrossLocks(workingDir, runner, input, verifier, verifier);
  }

  protected void runInAndVerifyIgnoreOutput(final File root, final String... inLines) throws IOException {
    final Processor<ProcessOutput> verifier = createPrimitiveExitCodeVerifier();
    runAndVerifyAcrossLocks(root, myRunner, inLines, verifier, verifier);
  }

  private void runAndVerifyAcrossLocks(@Nullable File workingDir, final String[] input, final Processor<ProcessOutput> verifier,
                                       final Processor<ProcessOutput> primitiveVerifier) throws IOException {
    workingDir = notNull(workingDir, myWcRoot);
    runAndVerifyAcrossLocks(workingDir, myRunner, input, verifier, primitiveVerifier);
  }

  public static void runAndVerifyAcrossLocks(File workingDir, final TestClientRunner runner, final String[] input,
    final Processor<ProcessOutput> verifier, final Processor<ProcessOutput> primitiveVerifier) throws IOException {
    for (int i = 0; i < 5; i++) {
      final ProcessOutput output = runner.runClient("svn", null, workingDir, input);
      if (output.getExitCode() != 0 && !isEmptyOrSpaces(output.getStderr())) {
        final String stderr = output.getStderr();
        if (stderr.contains("E155004") && stderr.contains("is already locked")) continue;
      }
      if (verifier.process(output)) continue;
      return;
    }
    primitiveVerifier.process(runner.runClient("svn", null, workingDir, input));
  }
}
