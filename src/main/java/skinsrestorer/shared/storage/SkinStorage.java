package skinsrestorer.shared.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import lombok.Setter;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;
import skinsrestorer.bukkit.SkinsRestorer;
import skinsrestorer.shared.exception.SkinRequestException;
import skinsrestorer.shared.utils.MojangAPI;
import skinsrestorer.shared.utils.MySQL;
import skinsrestorer.shared.utils.Property;
import skinsrestorer.shared.utils.ReflectionUtil;

import javax.sql.RowSet;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class SkinStorage {

    private Class<?> property;
    @Getter
    @Setter
    private MySQL mysql;
    private File folder;
    private boolean isBungee = false;
    private boolean isVelocity = false;
    private boolean isSponge = false;
    @Getter
    @Setter
    private MojangAPI mojangAPI;

    private void load() {
        try {
            property = Class.forName("org.spongepowered.api.profile.property.ProfileProperty");
            isSponge = true;
        } catch (Exception exe) {
            try {
                property = Class.forName("com.mojang.authlib.properties.Property");
            } catch (Exception e) {
                try {
                    property = Class.forName("net.md_5.bungee.connection.LoginResult$Property");
                    isBungee = true;
                } catch (Exception ex) {
                    try {
                        property = Class.forName("net.minecraft.util.com.mojang.authlib.properties.Property");
                    } catch (Exception exc) {
                        try {
                            property = Class.forName("com.velocitypowered.api.util.GameProfile$Property");
                            isVelocity = true;
                        } catch (Exception exce) {
                            System.out.println("[SkinsRestorer] Could not find a valid Property class! Plugin will not work properly");
                        }
                    }
                }
            }
        }
    }

    public SkinStorage() {
        this.load();
    }

    public void loadFolders(File pluginFolder) {
        folder = pluginFolder;
        File tempFolder = new File(folder.getAbsolutePath() + File.separator + "Skins" + File.separator);
        tempFolder.mkdirs();
        tempFolder = new File(folder.getAbsolutePath() + File.separator + "Players" + File.separator);
        tempFolder.mkdirs();
    }

    public void preloadDefaultSkins() {
        if (!Config.DEFAULT_SKINS_ENABLED)
            return;

        Config.DEFAULT_SKINS.forEach(skin -> {
            try {
                this.setSkinData(skin, this.getMojangAPI().getSkinProperty(this.getMojangAPI().getUUID(skin)));
            } catch (SkinRequestException e) {
                if (this.getSkinData(skin) == null)
                    System.out.println("§e[§2SkinsRestorer§e] §cDefault Skin '" + skin + "' request error: " + e.getReason());
            }
        });
    }

    public Object createProperty(String name, String value, String signature) {
        // use our own propery class if we are on skinsrestorer.sponge
        if (isSponge) {
            Property p = new Property();
            p.setName(name);
            p.setValue(value);
            p.setSignature(signature);
            return p;
        }

        try {
            return ReflectionUtil.invokeConstructor(property,
                    new Class<?>[]{String.class, String.class, String.class}, name, value, signature);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * This methods seeks out players actual skin (chosen or not) and returns
     * either null (if no skin data found) or the property object conatining all
     * the skin data.
     * <p>
     * Also, it schedules a skin update to stay up to date with skin changes.
     *
     * @param name   Player name to search skin for
     * @param silent Whether to throw errors or not
     * @return Property    Platform specific Property Object
     * @throws SkinRequestException If MojangAPI lookup errors
     **/
    public Object getOrCreateSkinForPlayer(final String name, boolean silent) throws SkinRequestException {
        String skin = getPlayerSkin(name);

        if (skin == null) {
            skin = name.toLowerCase();
        }

        Object textures = getSkinData(skin);

        if (textures != null) {
            return textures;
        }

        // No cached skin found, get from MojangAPI, save and return
        try {
            textures = this.getMojangAPI().getSkinProperty(this.getMojangAPI().getUUID(skin));
            setSkinData(skin, textures);
        } catch (SkinRequestException e) {
            if (!silent)
                throw new SkinRequestException(e.getReason());
        } catch (Exception e) {
            e.printStackTrace();
            if (!silent)
                throw new SkinRequestException(Locale.WAIT_A_MINUTE);
        }

        return textures;
    }

    public Object getOrCreateSkinForPlayer(final String name) throws SkinRequestException {
        return getOrCreateSkinForPlayer(name, false);
    }

    /*
     * Returns the custom skin name that player has set.
     *
     * Returns null if player has no custom skin set. (even if its his own name)
     */
    public String getPlayerSkin(String name) {
        name = name.toLowerCase();
        if (Config.USE_MYSQL) {
            RowSet crs = mysql.query("SELECT * FROM " + Config.MYSQL_PLAYERTABLE + " WHERE Nick=?", name);

            if (crs != null)
                try {
                    String skin = crs.getString("Skin");

                    if (skin.isEmpty() || skin.equalsIgnoreCase(name)) {
                        removePlayerSkin(name);
                        return null;
                    }

                    return skin;

                } catch (Exception e) {
                    e.printStackTrace();
                }

            return null;

        } else {
            File playerFile = new File(folder.getAbsolutePath() + File.separator + "Players" + File.separator + name + ".player");

            try {
                if (!playerFile.exists())
                    return null;

                BufferedReader buf = new BufferedReader(new FileReader(playerFile));

                String line, skin = null;
                if ((line = buf.readLine()) != null)
                    skin = line;

                buf.close();

                if (skin == null || skin.equalsIgnoreCase(name)) {
                    removePlayerSkin(name);
                    return null;
                }

                return skin;

            } catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }
    }

    /**
     * Returns property object containing skin data of the wanted skin
     **/
    public Object getSkinData(String name, boolean updateOutdated) {
        name = name.toLowerCase();

        if (Config.USE_MYSQL) {
            RowSet crs = mysql.query("SELECT * FROM " + Config.MYSQL_SKINTABLE + " WHERE Nick=?", name);
            if (crs != null)
                try {
                    String value = crs.getString("Value");
                    String signature = crs.getString("Signature");
                    String timestamp = crs.getString("timestamp");

                    if (updateOutdated && isOld(Long.parseLong(timestamp))) {
                        Object skin = this.getMojangAPI().getSkinProperty(this.getMojangAPI().getUUID(name));
                        if (skin != null) {
                            this.setSkinData(name, skin);
                            return skin;
                        }
                    }

                    return createProperty("textures", value, signature);

                } catch (Exception e) {
                    removeSkinData(name);
                    System.out.println("[SkinsRestorer] Unsupported player format.. removing (" + name + ").");
                }

            return null;
        } else {
            File skinFile = new File(folder.getAbsolutePath() + File.separator + "Skins" + File.separator + name + ".skin");

            try {
                if (!skinFile.exists())
                    return null;

                BufferedReader buf = new BufferedReader(new FileReader(skinFile));

                String line, value = "", signature = "", timestamp = "";
                for (int i = 0; i < 3; i++)
                    if ((line = buf.readLine()) != null)
                        if (value.isEmpty()) {
                            value = line;
                        } else if (signature.isEmpty()) {
                            signature = line;
                        } else {
                            timestamp = line;
                        }
                buf.close();

                if (updateOutdated && isOld(Long.parseLong(timestamp))) {
                    Object skin = this.getMojangAPI().getSkinProperty(this.getMojangAPI().getUUID(name));
                    if (skin != null) {
                        this.setSkinData(name, skin);
                        return skin;
                    }
                }

                return this.createProperty("textures", value, signature);

            } catch (Exception e) {
                removeSkinData(name);
                System.out.println("[SkinsRestorer] Unsupported player format.. removing (" + name + ").");
            }

            return null;
        }
    }

    public Object getSkinData(String name) {
        return this.getSkinData(name, true);
    }

    private boolean isOld(long timestamp) {
        return timestamp + TimeUnit.MINUTES.toMillis(Config.SKIN_EXPIRES_AFTER) <= System.currentTimeMillis();
    }

    /**
     * Removes custom players skin name from database
     *
     * @param name - Players name
     **/
    public void removePlayerSkin(String name) {
        name = name.toLowerCase();
        if (Config.USE_MYSQL) {
            mysql.execute("DELETE FROM " + Config.MYSQL_PLAYERTABLE + " WHERE Nick=?", name);
        } else {
            File playerFile = new File(folder.getAbsolutePath() + File.separator + "Players" + File.separator + name + ".player");

            if (playerFile.exists())
                playerFile.delete();
        }
    }

    /**
     * Removes skin data from database
     *
     * @param name - Skin name
     **/
    public void removeSkinData(String name) {
        name = name.toLowerCase();
        if (Config.USE_MYSQL) {
            mysql.execute("DELETE FROM " + Config.MYSQL_SKINTABLE + " WHERE Nick=?", name);
        } else {
            File skinFile = new File(folder.getAbsolutePath() + File.separator + "Skins" + File.separator + name + ".skin");

            if (skinFile.exists())
                skinFile.delete();
        }
    }

    /**
     * Saves custom player's skin name to database
     *
     * @param name - Players name
     * @param skin - Skin name
     **/
    public void setPlayerSkin(String name, String skin) {
        name = name.toLowerCase();
        if (Config.USE_MYSQL) {
            RowSet crs = mysql.query("SELECT * FROM " + Config.MYSQL_PLAYERTABLE + " WHERE Nick=?", name);

            if (crs == null)
                mysql.execute("INSERT INTO " + Config.MYSQL_PLAYERTABLE + " (Nick, Skin) VALUES (?,?)", name, skin);
            else
                mysql.execute("UPDATE " + Config.MYSQL_PLAYERTABLE + " SET Skin=? WHERE Nick=?", skin, name);
        } else {
            File playerFile = new File(folder.getAbsolutePath() + File.separator + "Players" + File.separator + name + ".player");

            try {
                if (skin.equalsIgnoreCase(name) && playerFile.exists()) {
                    playerFile.delete();
                    return;
                }

                if (!playerFile.exists())
                    playerFile.createNewFile();

                FileWriter writer = new FileWriter(playerFile);

                writer.write(skin);
                writer.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Saves skin data to database
     *
     * @param name      - Skin name
     * @param textures  - Property object
     * @param timestamp - timestamp string in millis
     **/
    public void setSkinData(String name, Object textures, String timestamp) {
        name = name.toLowerCase();
        String value = "";
        String signature = "";
        try {
            value = (String) ReflectionUtil.invokeMethod(textures, "getValue");
            signature = (String) ReflectionUtil.invokeMethod(textures, "getSignature");
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (Config.USE_MYSQL) {
            RowSet crs = mysql.query("SELECT * FROM " + Config.MYSQL_SKINTABLE + " WHERE Nick=?", name);

            if (crs == null)
                mysql.execute("INSERT INTO " + Config.MYSQL_SKINTABLE + " (Nick, Value, Signature, timestamp) VALUES (?,?,?,?)",
                        name, value, signature, timestamp);
            else
                mysql.execute("UPDATE " + Config.MYSQL_SKINTABLE + " SET Value=?, Signature=?, timestamp=? WHERE Nick=?",
                        value, signature, timestamp, name);
        } else {
            File skinFile = new File(folder.getAbsolutePath() + File.separator + "Skins" + File.separator + name + ".skin");

            try {
                if (value.isEmpty() || signature.isEmpty() || timestamp.isEmpty())
                    return;

                if (!skinFile.exists())
                    skinFile.createNewFile();

                FileWriter writer = new FileWriter(skinFile);

                writer.write(value + "\n" + signature + "\n" + timestamp);
                writer.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void setSkinData(String name, Object textures) {
        setSkinData(name, textures, Long.toString(System.currentTimeMillis()));
    }

    public Map<String, Object> getSkins(int number) {
        if (Config.USE_MYSQL) {
            Map<String, Object> list = new TreeMap<>();
            RowSet crs = mysql.query("SELECT * FROM " + Config.MYSQL_SKINTABLE + " ORDER BY `Nick`");
            int i = 0;
            try {
                do {
                    if (i >= number)
                        list.put(crs.getString("Nick"), createProperty("textures", crs.getString("Value"), crs.getString("Signature")));
                    i++;
                } while (crs.next());
            } catch (java.sql.SQLException ignored) {
            }
            return list;
        } else {
            Map<String, Object> list = new TreeMap<>();
            String path = SkinsRestorer.getInstance().getDataFolder() + "/Skins/";
            File folder = new File(path);
            String[] fileNames = folder.list();

            if (fileNames == null)
                return list;

            Arrays.sort(fileNames);
            int i = 0;
            for (String file : fileNames) {
                String skinName = file.replace(".skin", "");
                if (i >= number) {
                    list.put(skinName, this.getSkinData(skinName, false));
                }
                i++;
            }
            return list;
        }
    }

    public boolean forceUpdateSkinData(String skin) {
        try {
            Object textures = this.getMojangAPI().getSkinPropertyBackup(this.getMojangAPI().getUUIDBackup(skin));
            if (textures != null) {
                this.setSkinData(skin, textures);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // If clear is true, it doesn't return the custom skin a user has set
    public String getDefaultSkinNameIfEnabled(String player, boolean clear) {
        if (Config.DEFAULT_SKINS_ENABLED) {
            // dont return default skin name for premium players if enabled
            if (!Config.DEFAULT_SKINS_PREMIUM) {
                // check if player is premium
                try {
                    if (this.getMojangAPI().getUUID(player) != null) {
                        // player is premium, return his skin name instead of default skin
                        return player;
                    }
                } catch (SkinRequestException ignored) {
                    // Player is not premium catching exception here to continue returning a default skin name
                }
            }

            // return default skin name if user has no custom skin set or we want to clear to default
            if (this.getPlayerSkin(player) == null || clear) {
                List<String> skins = Config.DEFAULT_SKINS;
                int randomNum = (int) (Math.random() * skins.size());
                String randomSkin = skins.get(randomNum);
                // return player name if there are no default skins set
                return randomSkin != null ? randomSkin : player;
            }
        }

        // return the player name if we want to clear the skin
        if (clear)
            return player;

        // return the custom skin user has set
        String skin = this.getPlayerSkin(player);

        // null if player has no custom skin, we'll return his name then
        return skin == null ? player : skin;
    }

    public String getDefaultSkinNameIfEnabled(String player) {
        return getDefaultSkinNameIfEnabled(player, false);
    }
}