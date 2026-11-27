const path = require('node:path');
const {
  AndroidConfig,
  CodeGenerator,
  withAndroidManifest,
  withGradleProperties,
  withMainActivity,
  withMainApplication,
  withSettingsGradle,
} = require('expo/config-plugins');

const sharedProjects = `['scheduler-core', 'scheduler-android'].each { name ->
    include ":$name"
    project(":$name").projectDir = new File(rootDir, "../config/gradle/$name")
}

// Keep the standalone and Expo builds' intermediates in their respective projects.
gradle.beforeProject { subproject ->
    if (subproject.path in [':scheduler-core', ':scheduler-android']) {
        subproject.layout.buildDirectory.set(new File(rootDir, 'build/shared/' + subproject.name))
    }
}`;

function patchTemplate(source, from, to) {
  if (source.includes(to)) return source;
  if (!source.includes(from)) throw new Error(`Unsupported Expo Android template: expected ${from}`);
  return source.replace(from, to);
}

function withNativeManifest(config) {
  return withAndroidManifest(config, async (mod) => {
    const source = await AndroidConfig.Manifest.readAndroidManifestAsync(
      path.resolve(mod.modRequest.projectRoot, 'src/runtime/AndroidManifest.xml'),
    );
    const nativeApp = source.manifest.application[0];
    const host = AndroidConfig.Manifest.getMainApplicationOrThrow(mod.modResults);
    for (const name of ['android:allowBackup', 'android:fullBackupContent', 'android:dataExtractionRules']) {
      host.$[name] = nativeApp.$[name];
    }

    // WorkManager gets the same custom factory as the standalone application.
    const provider = nativeApp.provider.find(
      (entry) => entry.$['android:name'] === 'androidx.startup.InitializationProvider',
    );
    mod.modResults.manifest.$['xmlns:tools'] = source.manifest.$['xmlns:tools'];
    host.provider = (host.provider ?? []).filter((entry) => entry.$['android:name'] !== provider.$['android:name']);
    host.provider.push(provider);

    // ReactActivity owns the launcher. Android shares still use the original Compose Activity.
    const activity = AndroidConfig.Manifest.getMainActivityOrThrow(source);
    const nativeName = 'com.pebblepile.android.MainActivity';
    activity.$['android:name'] = nativeName;
    activity.$['android:theme'] = nativeApp.$['android:theme'];
    activity['intent-filter'] = activity['intent-filter'].filter((filter) =>
      filter.action.some((action) => action.$['android:name'] === 'android.intent.action.SEND'),
    );
    host.activity = host.activity.filter((entry) => entry.$['android:name'] !== nativeName);
    host.activity.push(activity);
    return mod;
  });
}

module.exports = function withPebbleAndroid(config) {
  config = withSettingsGradle(config, (mod) => {
    mod.modResults.contents = CodeGenerator.mergeContents({
      src: mod.modResults.contents,
      newSrc: sharedProjects,
      tag: 'pebblepile-scheduler',
      anchor: /^include ':app'/m,
      offset: 1,
      comment: '//',
    }).contents;
    return mod;
  });
  config = withGradleProperties(config, (mod) => {
    // Room 2.6 uses KSP1 in the standalone build. KSP 2.1.20 defaults to KSP2.
    const properties = { 'android.minSdkVersion': '26', 'ksp.useKSP2': 'false' };
    mod.modResults = mod.modResults.filter((entry) => !Object.hasOwn(properties, entry.key));
    for (const [key, value] of Object.entries(properties)) {
      mod.modResults.push({ type: 'property', key, value });
    }
    return mod;
  });
  config = withMainApplication(config, (mod) => {
    mod.modResults.contents = patchTemplate(mod.modResults.contents,
      'import android.app.Application', 'import com.pebblepile.android.PebblePileApplication');
    mod.modResults.contents = patchTemplate(mod.modResults.contents,
      'MainApplication : Application()', 'MainApplication : PebblePileApplication()');
    return mod;
  });
  config = withMainActivity(config, (mod) => {
    // Pass the Activity's saved state through to the embedded Compose navigation host.
    mod.modResults.contents = patchTemplate(mod.modResults.contents,
      'super.onCreate(null)', 'super.onCreate(savedInstanceState)');
    // SDK 55 backgrounds the root task directly on Android 8–11. Give child screens Back first.
    mod.modResults.contents = CodeGenerator.mergeContents({
      src: mod.modResults.contents,
      newSrc: [
        '          if (onBackPressedDispatcher.hasEnabledCallbacks()) {',
        '              onBackPressedDispatcher.onBackPressed()',
        '              return',
        '          }',
      ].join('\n'),
      tag: 'pebblepile-compose-back',
      anchor: /if \(Build\.VERSION\.SDK_INT <= Build\.VERSION_CODES\.R\)/,
      offset: 1,
      comment: '//',
    }).contents;
    return mod;
  });
  return withNativeManifest(config);
};
