package com.zomdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zomdroid.game.InstallationPreset;
import com.zomdroid.game.PresetManager;
import com.zomdroid.gog.GogInstallerExtractor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class GameDirImportTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void kindGameDirConstantMatches() {
        assertEquals("GAME_DIR", GogInstallerExtractor.KIND_GAME_DIR);
    }

    @Test
    public void isGameRootDetectsMarkers() throws Exception {
        File emptyDir = temp.newFolder("empty");
        assertFalse(FileUtils.isGameRoot(emptyDir));

        File pzDir = temp.newFolder("pz_root");
        new File(pzDir, "ProjectZomboid64.json").createNewFile();
        assertTrue(FileUtils.isGameRoot(pzDir));

        File pzJarDir = temp.newFolder("pz_jar");
        new File(pzJarDir, "projectzomboid.jar").createNewFile();
        assertTrue(FileUtils.isGameRoot(pzJarDir));
    }

    @Test
    public void findGameRootFindsNestedDirectory() throws Exception {
        File baseDir = temp.newFolder("downloads");
        File wrapper = new File(baseDir, "nested/PZ_Game");
        assertTrue(wrapper.mkdirs());
        new File(wrapper, "ProjectZomboid64.json").createNewFile();
        new File(wrapper, "zombie").mkdirs();

        File found = FileUtils.findGameRoot(baseDir);
        assertNotNull(found);
        assertEquals(wrapper.getAbsolutePath(), found.getAbsolutePath());
    }

    @Test
    public void findGameRootReturnsNullWhenNoPzFound() throws Exception {
        File baseDir = temp.newFolder("random_folder");
        File sub = new File(baseDir, "sub/dir");
        assertTrue(sub.mkdirs());
        new File(sub, "some_file.txt").createNewFile();

        assertNull(FileUtils.findGameRoot(baseDir));
    }

    @Test
    public void presetDetectionWorksOnGameFolders() throws Exception {
        File b41Dir = temp.newFolder("b41");
        new File(b41Dir, "ProjectZomboid64.json").createNewFile();
        InstallationPreset preset41 = PresetManager.detectFromGameDir(b41Dir);
        assertNotNull(preset41);
        assertEquals("Build 41", preset41.name);

        File b42Dir = temp.newFolder("b42");
        new File(b42Dir, "ProjectZomboid64.json").createNewFile();
        new File(b42Dir, "imgui-1.86.jar").createNewFile();
        InstallationPreset preset42 = PresetManager.detectFromGameDir(b42Dir);
        assertNotNull(preset42);
        assertEquals("Build 42", preset42.name);

        File b4212Dir = temp.newFolder("b4212");
        new File(b4212Dir, "ProjectZomboid64.json").createNewFile();
        File androidDir = new File(b4212Dir, "android");
        assertTrue(androidDir.mkdirs());
        InstallationPreset preset4212 = PresetManager.detectFromGameDir(b4212Dir);
        assertNotNull(preset4212);
        assertEquals("Build 42.12+", preset4212.name);
    }
}
