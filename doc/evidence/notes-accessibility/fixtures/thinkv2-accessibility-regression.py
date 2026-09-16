import pathlib,subprocess
adb=['/Users/orderly_ray/Library/Android/sdk/platform-tools/adb','-P','5056','-s','127.0.0.1:5595'];out=pathlib.Path('doc/evidence/notes-accessibility/regression');out.mkdir(exist_ok=True)
for permission in ['READ_CALENDAR','WRITE_CALENDAR']:subprocess.run(adb+['shell','pm','grant','com.example.thinkv2.test','android.permission.'+permission],check=True)
cases=['voice.VoiceCommandStateTest','voice.VoiceCommandLifecycleTest#realMicrophoneCommandCaptureBackgroundAndExplicitExitKeepDraft','voice.VoiceCommandLifecycleTest#deniedActualMicrophonePermissionCannotProduceCommandOrChangeText','ExampleInstrumentedTest','AndroidPersistenceTest','AndroidLifecycleTest','NotesUiTest','ReminderUiTest','AndroidReminderTest','backup.AndroidBackupTest','calendar.CalendarImportDeviceTest#actualProviderUiFaithfulImportCancelIdempotenceAndSourceUnchanged','relations.RelationsDeviceTest#realUiPairsSameTitlesExactNavigationRenameTrashRestoreAndRemove','backup.BackupRestoreStateDeviceTest#actualRestoreButtonDisablesInMemoryAiAndReloadsVocabularyAndRejectsStalePreview','voice.VoiceLifecycleTest#actualMicrophoneBackgroundAndCancelKeepExistingDraft','voice.VoiceLifecycleTest#deniedPlatformPermissionDoesNotChangeText']
for case in cases:
 prior=out/(case+'.log')
 if prior.exists() and 'OK (' in prior.read_text() and 'FAILURES' not in prior.read_text():continue
 subprocess.run(adb+['shell','pm','clear','com.example.thinkv2'],check=True,capture_output=True)
 result=subprocess.run(adb+['shell','am','instrument','-w','-e','class','com.example.thinkv2.'+case,'com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True)
 (out/(case+'.log')).write_text(result.stdout+result.stderr);ok='OK (' in result.stdout and 'FAILURES' not in result.stdout;print(case,'PASS' if ok else 'FAIL',flush=True)
 if not ok:raise SystemExit(1)
