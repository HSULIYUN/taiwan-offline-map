// 台灣離線地圖服務：自動從 GitHub 下載最新的地圖資料，並執行 GraphHopper 路線服務。
//
// 需要 Java 21 以上，不用編譯，直接執行（請在專案資料夾，也就是有 config/ 的那一層）：
//   java bin/MapService.java run      啟動服務：先檢查更新再啟動，之後每隔一段時間檢查一次
//   java bin/MapService.java update   只下載最新版地圖資料，不啟動服務
//   java bin/MapService.java status   顯示目前狀態
//
// 平常不用自己打這些指令：Mac 雙擊 start.command、Windows 雙擊 install.bat、
// Linux 執行 bin/install-service.sh 都會幫你處理好。設定在 config/map.properties。

import java.io.*;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MapService {

    static final String GH_MAIN_CLASS = "com.graphhopper.application.GraphHopperApplication";
    static final String DEFAULT_GH_VERSION = "10.0";
    static final String DEFAULT_GH_JAR_URL =
            "https://github.com/graphhopper/graphhopper/releases/download/{v}/graphhopper-web-{v}.jar";
    // 新版上線後實際查一條路線確認正常：台北車站 → 高雄車站，距離要在合理範圍
    static final String ROUTE_FROM = "25.0478,121.5170", ROUTE_TO = "22.6394,120.3025";
    static final long ROUTE_MIN_M = 300_000, ROUTE_MAX_M = 450_000;
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    static Path root, releases, logs, tools, configDir;
    static final Properties cfg = new Properties();
    static HttpClient web;      // 對外（GitHub），會使用系統設定的 proxy
    static HttpClient local;    // 本機路線服務，不經過 proxy
    static PrintWriter logFile;
    static Process gh;
    static String ghRelease;

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "run";
        // 終端機是 ASCII（例如 Linux 服務的 LANG=C）時改用 UTF-8，中文才不會變成問號
        String enc = System.getProperty("stdout.encoding", "");
        if (enc.isEmpty() || enc.matches("(?i)(US-ASCII|ANSI_X3\\.4-1968|ASCII)")) {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8));
        }
        root = Path.of("").toAbsolutePath();
        configDir = root.resolve("config");
        if (!Files.isRegularFile(configDir.resolve("config.yml"))) {
            System.err.println("請在地圖專案資料夾裡執行（找不到 config/config.yml）：" + root);
            System.exit(2);
        }
        releases = root.resolve("releases");
        logs = root.resolve("logs");
        tools = root.resolve("tools");
        for (Path p : List.of(releases, logs, tools)) Files.createDirectories(p);
        Path props = configDir.resolve("map.properties");
        if (Files.exists(props)) {
            try (Reader r = Files.newBufferedReader(props, UTF_8)) { cfg.load(r); }
        }
        rotate(logs.resolve("service.log"));
        logFile = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(logs.resolve("service.log").toFile(), true), UTF_8), true);
        web = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(30)).build();
        local = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        int code = switch (cmd) {
            case "run" -> run();
            case "update" -> update();
            case "status" -> status();
            default -> {
                System.out.println("用法：java bin/MapService.java [run|update|status]");
                yield 2;
            }
        };
        System.exit(code);
    }

    // ================================================================ 指令

    static int run() throws Exception {
        if (!acquireLock()) {
            log("地圖服務已經在執行中，不需要再開一次。");
            return 0;
        }
        log("===== 地圖服務啟動 =====");
        killOrphanGh();
        if (healthy()) {
            log("錯誤：埠 " + port() + " 已被其他程式占用。請關掉那個程式，或在 config/map.properties 改 port。");
            return 1;
        }
        cleanupUnfinished();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("地圖服務關閉中…");
            stopGh();
        }));

        String cur = currentRelease();
        String fresh = downloadIfNewer(cur);
        if (fresh != null) {
            activate(fresh, cur);
        } else if (cur == null) {
            log("錯誤：本機還沒有地圖資料，也無法從 GitHub 下載。請確認網路連線，以及 config/map.properties 的 github.repo。");
            return 1;
        } else if (startGh(cur) && !routeCheck()) {
            log("注意：路線查詢結果不正常，請看 logs/graphhopper.log");
        }
        if (gh == null) {
            log("錯誤：路線服務無法啟動，請看 logs/graphhopper.log");
            return 1;
        }
        prune();
        log("地圖服務就緒：http://127.0.0.1:" + port() + "（地圖版本 " + ghRelease + "）");

        long interval = cfgInt("check.interval.minutes", 0) > 0   // 分鐘設定只給測試用
                ? Duration.ofMinutes(cfgInt("check.interval.minutes", 0)).toMillis()
                : Duration.ofHours(Math.max(1, cfgInt("check.interval.hours", 24))).toMillis();
        long nextCheck = System.currentTimeMillis() + interval;
        int restarts = 0;
        while (true) {
            Thread.sleep(60_000);
            if (gh == null || !gh.isAlive()) {
                restarts++;
                log("路線服務意外停止，重新啟動（第 " + restarts + " 次）…");
                if (!startGh(currentRelease()) && restarts >= 5) {
                    log("錯誤：路線服務一直無法啟動，請看 logs/graphhopper.log");
                    return 1;
                }
            }
            // 用牆上時間判斷：電腦睡眠醒來後，超過檢查時間就會馬上檢查
            if (System.currentTimeMillis() >= nextCheck) {
                nextCheck = System.currentTimeMillis() + interval;
                String c = currentRelease();
                String n = downloadIfNewer(c);
                if (n != null && activate(n, c)) prune();
            }
        }
    }

    static int update() throws Exception {
        if (!acquireLock()) {
            log("地圖服務正在執行中，它會自己定期更新，不需要另外執行 update。");
            return 0;
        }
        cleanupUnfinished();
        String cur = currentRelease();
        String fresh = downloadIfNewer(cur);
        if (fresh != null) {
            setCurrent(fresh);
            prune();
            log("已更新到 " + fresh + "，下次啟動服務時生效。");
            return 0;
        }
        if (cur == null) {
            log("錯誤：本機沒有地圖資料，也無法從 GitHub 下載。");
            return 1;
        }
        return 0;
    }

    static int status() {
        String cur = currentRelease();
        System.out.println("專案資料夾：" + root);
        System.out.println("下載來源：" + Objects.requireNonNullElse(repo(), "（未設定 github.repo）"));
        if (cur == null) {
            System.out.println("本機地圖資料：無");
        } else {
            Properties p = releaseInfo(cur);
            System.out.println("本機地圖資料：" + cur + "（OSM 資料日期 " + p.getProperty("osm_data_date", "不明") + "）");
        }
        System.out.println("路線服務：" + (healthy() ? "執行中 http://127.0.0.1:" + port() : "沒有在執行"));
        try {
            Properties remote = parse(fetchText(latestUrl()));
            System.out.println("GitHub 最新版：" + remote.getProperty("release"));
        } catch (Exception e) {
            System.out.println("GitHub 最新版：無法取得（" + brief(e) + "）");
        }
        return 0;
    }

    // ================================================================ 下載

    /** 有比 cur 新的版本就下載並解開到 releases/，回傳版本名稱；沒有或失敗回傳 null（不會丟例外）。 */
    static String downloadIfNewer(String cur) {
        Properties remote;
        try {
            remote = parse(fetchText(latestUrl()));
        } catch (Exception e) {
            log("無法檢查新版（" + brief(e) + "）" + (cur != null ? "，先使用本機的 " + cur : ""));
            return null;
        }
        String rel = remote.getProperty("release", "").trim();
        if (!rel.matches("[0-9A-Za-z._-]+")) {
            log("GitHub 上的版本資訊格式不對，略過這次更新");
            return null;
        }
        if (cur != null && rel.compareTo(cur) <= 0) {
            log("地圖資料已是最新版（" + cur + "）");
            return null;
        }
        if (isBad(rel)) {
            log("最新版 " + rel + " 之前在這台電腦驗證失敗過，略過，繼續使用 " + cur);
            return null;
        }
        log("發現新版地圖資料 " + rel + "（OSM 資料日期 " + remote.getProperty("osm_data_date", "不明") + "），開始下載…");
        Path building = releases.resolve(rel + ".building");
        try {
            download(rel, remote, building);
            return rel;
        } catch (Exception e) {
            log("下載新版失敗（" + brief(e) + "）" + (cur != null ? "，繼續使用 " + cur : ""));
            deleteQuietly(building);
            return null;
        }
    }

    static void download(String rel, Properties remote, Path building) throws Exception {
        String[] files = remote.getProperty("files", "").split(",");
        long need = 0;
        for (String f : files) need += Long.parseLong(remote.getProperty("size." + f.trim(), "0"));
        long free = Files.getFileStore(releases).getUsableSpace();
        if (free < need * 3) {
            throw new IOException("硬碟空間不足，需要約 " + mb(need * 3) + "，只剩 " + mb(free));
        }
        deleteQuietly(building);
        Files.createDirectories(building);
        for (String f : files) {
            f = f.trim();
            if (!f.matches("[0-9A-Za-z._-]+")) throw new IOException("檔名不合法：" + f);
            String sha = remote.getProperty("sha256." + f, "").trim();
            if (sha.isEmpty()) throw new IOException("版本資訊缺少 " + f + " 的檢查碼");
            Path target = building.resolve(f);
            downloadFile(assetUrl(rel, f), target, Long.parseLong(remote.getProperty("size." + f, "-1")), sha);
            if (f.endsWith(".zip")) {
                unzip(target, building);
                Files.delete(target);
            } else if (f.endsWith(".gz")) {
                try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(target)))) {
                    Files.copy(in, building.resolve(f.substring(0, f.length() - 3)), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.delete(target);
            }
        }
        if (!Files.isRegularFile(building.resolve("graph-cache/properties.txt"))) {
            throw new IOException("下載的資料裡沒有路網（graph-cache）");
        }
        ensureGhJar(remote.getProperty("graphhopper_version", DEFAULT_GH_VERSION).trim());
        Files.writeString(building.resolve("release.properties"), toText(remote), UTF_8);
        Path dest = releases.resolve(rel);
        deleteQuietly(dest);
        Files.move(building, dest);
        log("新版 " + rel + " 下載完成");
    }

    static void downloadFile(String url, Path target, long expectedSize, String sha256) throws Exception {
        String name = target.getFileName().toString().replaceAll("\\.download$", "");
        HttpResponse<InputStream> resp = web.send(get(url), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("下載 " + name + " 失敗：HTTP " + resp.statusCode());
        }
        long total = expectedSize > 0 ? expectedSize : resp.headers().firstValueAsLong("content-length").orElse(-1);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Path part = target.resolveSibling(name + ".part");
        long done = 0;
        int lastPct = 0;
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct >= lastPct + 20 && pct < 100) {
                    lastPct = pct - pct % 20;
                    log("  " + name + " " + lastPct + "%");
                }
            }
        }
        if (sha256 != null && !HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(sha256)) {
            Files.deleteIfExists(part);
            throw new IOException(name + " 檢查碼不符，檔案可能下載不完整");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        log("  " + name + " 完成（" + mb(done) + "）");
    }

    static Path ensureGhJar(String version) throws Exception {
        if (!version.matches("[0-9A-Za-z._-]+")) throw new IOException("GraphHopper 版本格式不對：" + version);
        Path jar = tools.resolve("graphhopper-web-" + version + ".jar");
        if (validGhJar(jar)) return jar;
        log("下載 GraphHopper " + version + " …");
        Path tmp = tools.resolve(jar.getFileName() + ".download");
        downloadFile(cfgStr("graphhopper.jar.url", DEFAULT_GH_JAR_URL).replace("{v}", version), tmp, -1, null);
        if (!validGhJar(tmp)) {
            Files.deleteIfExists(tmp);
            throw new IOException("下載的 GraphHopper 檔案不正確");
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    static boolean validGhJar(Path p) {
        if (!Files.isRegularFile(p)) return false;
        try (JarFile jf = new JarFile(p.toFile())) {
            return jf.getManifest() != null
                    && GH_MAIN_CLASS.equals(jf.getManifest().getMainAttributes().getValue("Main-Class"));
        } catch (IOException e) {
            return false;
        }
    }

    // ================================================================ 版本切換

    /** 切到新版並啟動、驗證；有問題就退回舊版。回傳新版是否成功上線。 */
    static boolean activate(String newRel, String oldRel) {
        log("切換到 " + newRel + (oldRel != null ? "（原本是 " + oldRel + "）" : ""));
        stopGh();
        setCurrent(newRel);
        if (startGh(newRel) && routeCheck()) {
            log("新版 " + newRel + " 運作正常 ✓");
            return true;
        }
        markBad(newRel);
        stopGh();
        if (oldRel != null) {
            log("新版有問題，退回 " + oldRel);
            setCurrent(oldRel);
            startGh(oldRel);
        }
        return false;
    }

    static String currentRelease() {
        String r = null;
        try {
            Path txt = root.resolve("current.txt");
            Path link = root.resolve("current");
            if (Files.exists(txt)) r = Files.readString(txt, UTF_8).trim();
            else if (Files.isSymbolicLink(link)) r = Files.readSymbolicLink(link).getFileName().toString();
        } catch (IOException ignored) {
        }
        if (r != null && isRelease(r)) return r;
        // 指標壞掉時，用最新的完整、沒有驗證失敗過的版本
        return listReleases().stream().filter(x -> !isBad(x)).findFirst().orElse(null);
    }

    static boolean isRelease(String r) {
        return !r.isEmpty() && !r.endsWith(".building")
                && Files.isRegularFile(releases.resolve(r).resolve("graph-cache/properties.txt"));
    }

    /** 本機完整的版本，新的在前。 */
    static List<String> listReleases() {
        try (Stream<Path> s = Files.list(releases)) {
            return s.map(p -> p.getFileName().toString()).filter(MapService::isRelease)
                    .sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static void setCurrent(String rel) {
        try {
            Path tmp = root.resolve("current.txt.tmp");
            Files.writeString(tmp, rel + System.lineSeparator(), UTF_8);
            try {
                Files.move(tmp, root.resolve("current.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, root.resolve("current.txt"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        updateCurrentLink(rel);
    }

    /** 維護 current 連結，方便直接讀檔案的程式使用固定路徑；失敗不影響路線服務。 */
    static void updateCurrentLink(String rel) {
        Path link = root.resolve("current");
        try {
            if (WINDOWS) {
                // rmdir 不加 /s：只會移除目錄連接（junction），不會刪到真正的資料夾
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) exec("cmd", "/c", "rmdir", "current");
                exec("cmd", "/c", "mklink", "/J", "current", "releases\\" + rel);
            } else {
                if (Files.isSymbolicLink(link)) Files.delete(link);
                else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                    log("注意：current 不是連結，沒有更新它");
                    return;
                }
                Files.createSymbolicLink(link, Path.of("releases", rel));
            }
        } catch (Exception e) {
            log("注意：無法更新 current 連結（" + brief(e) + "），不影響路線服務");
        }
    }

    static void prune() {
        int keep = Math.max(1, cfgInt("keep.releases", 2));
        String cur = currentRelease();
        int kept = 0;
        for (String r : listReleases()) {
            if (r.equals(cur) || r.equals(ghRelease)) {
                kept++;
            } else if (isBad(r)) {
                deleteQuietly(releases.resolve(r));
                log("刪除驗證失敗的版本 " + r);
            } else if (kept < keep) {
                kept++;
            } else {
                deleteQuietly(releases.resolve(r));
                log("刪除舊版本 " + r);
            }
        }
    }

    static void cleanupUnfinished() {
        try (Stream<Path> s = Files.list(releases)) {
            s.filter(p -> p.getFileName().toString().endsWith(".building")).forEach(MapService::deleteQuietly);
        } catch (IOException ignored) {
        }
    }

    static boolean isBad(String rel) {
        try {
            return Files.readAllLines(logs.resolve("bad-releases.txt"), UTF_8).contains(rel);
        } catch (IOException e) {
            return false;
        }
    }

    static void markBad(String rel) {
        try {
            Files.writeString(logs.resolve("bad-releases.txt"), rel + System.lineSeparator(), UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    static Properties releaseInfo(String rel) {
        Properties p = new Properties();
        Path dir = releases.resolve(rel);
        try (Reader r = Files.newBufferedReader(dir.resolve("release.properties"), UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            // 舊版資料夾只有 manifest.json
            try {
                String json = Files.readString(dir.resolve("manifest.json"), UTF_8);
                Matcher m = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
                while (m.find()) p.setProperty(m.group(1), m.group(2));
            } catch (IOException ignored) {
            }
        }
        return p;
    }

    // ================================================================ 路線服務

    static boolean startGh(String rel) {
        if (rel == null) return false;
        try {
            Path jar = ensureGhJar(releaseInfo(rel).getProperty("graphhopper_version", DEFAULT_GH_VERSION).trim());
            Path yml = logs.resolve("graphhopper-runtime.yml");
            Files.write(yml, runtimeConfig(releases.resolve(rel).resolve("graph-cache")), UTF_8);
            Path ghLog = logs.resolve("graphhopper.log");
            rotate(ghLog);
            String java = ProcessHandle.current().info().command().orElse("java");
            ProcessBuilder pb = new ProcessBuilder(java, "-Xmx" + cfgStr("java.xmx", "2g"),
                    "-jar", jar.toString(), "server", yml.toString())
                    .directory(configDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(ghLog.toFile()));
            log("啟動路線服務（地圖版本 " + rel + "）…");
            gh = pb.start();
            Files.writeString(logs.resolve("graphhopper.pid"), String.valueOf(gh.pid()), UTF_8);
            for (int i = 0; i < 180; i++) {
                if (healthy()) {
                    ghRelease = rel;
                    log("路線服務已就緒");
                    return true;
                }
                if (!gh.isAlive()) break;
                Thread.sleep(1000);
            }
            log("路線服務啟動失敗，請看 logs/graphhopper.log");
        } catch (Exception e) {
            log("路線服務啟動失敗（" + brief(e) + "）");
        }
        stopGh();
        return false;
    }

    /** 由 config/config.yml 產生實際使用的設定：換掉路網位置、埠號、綁定位址。 */
    static List<String> runtimeConfig(Path graphDir) throws IOException {
        String graph = graphDir.toAbsolutePath().toString().replace('\\', '/');
        List<String> out = new ArrayList<>();
        String section = "";
        for (String line : Files.readAllLines(configDir.resolve("config.yml"), UTF_8)) {
            String t = line.strip();
            String indent = line.substring(0, line.length() - line.stripLeading().length());
            if (t.startsWith("datareader.file:")) continue;
            if (t.startsWith("application_connectors:")) section = "app";
            if (t.startsWith("admin_connectors:")) section = "admin";
            if (t.startsWith("graph.location:")) line = indent + "graph.location: \"" + graph + "\"";
            else if (t.startsWith("port:")) line = indent + "port: " + (section.equals("admin") ? adminPort() : port());
            else if (t.startsWith("bind_host:")) line = indent + "bind_host: " + cfgStr("bind.host", "127.0.0.1");
            out.add(line);
        }
        return out;
    }

    static void stopGh() {
        Process p = gh;
        gh = null;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly().waitFor();
            } catch (InterruptedException ignored) {
            }
        }
        try {
            Files.deleteIfExists(logs.resolve("graphhopper.pid"));
        } catch (IOException ignored) {
        }
    }

    /** 上次地圖服務被強制結束時，留下來的路線服務要先關掉。 */
    static void killOrphanGh() {
        try {
            long pid = Long.parseLong(Files.readString(logs.resolve("graphhopper.pid"), UTF_8).trim());
            ProcessHandle.of(pid).ifPresent(h -> {
                String cmd = h.info().commandLine().orElse(h.info().command().orElse(""));
                if (cmd.contains("graphhopper-web") || cmd.matches("(?i).*java(\\.exe)?$")) {
                    log("關閉上次遺留的路線服務（PID " + pid + "）");
                    h.destroy();
                    try {
                        h.onExit().get(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        h.destroyForcibly();
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    static boolean healthy() {
        try {
            HttpResponse<String> r = local.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port() + "/health")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean routeCheck() {
        try {
            String url = "http://127.0.0.1:" + port() + "/route?profile=car&calc_points=false&point="
                    + ROUTE_FROM + "&point=" + ROUTE_TO;
            HttpResponse<String> r = local.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            Matcher m = Pattern.compile("\"distance\"\\s*:\\s*([0-9.]+)").matcher(r.body());
            if (r.statusCode() == 200 && m.find()) {
                double d = Double.parseDouble(m.group(1));
                if (d >= ROUTE_MIN_M && d <= ROUTE_MAX_M) {
                    log("路線測試：台北車站→高雄車站 約 " + Math.round(d / 1000) + " 公里 ✓");
                    return true;
                }
                log("路線測試距離不合理：" + Math.round(d) + " 公尺");
            } else {
                log("路線測試失敗：HTTP " + r.statusCode());
            }
        } catch (Exception e) {
            log("路線測試失敗（" + brief(e) + "）");
        }
        return false;
    }

    // ================================================================ GitHub 位址

    static String repo() {
        String r = cfgStr("github.repo", "");
        if (!r.isEmpty()) return r;
        // 直接讀 .git/config，不需要安裝 git（Windows 服務帳號常常找不到 git）
        try {
            boolean origin = false;
            for (String line : Files.readAllLines(root.resolve(".git/config"), UTF_8)) {
                String t = line.strip();
                if (t.startsWith("[")) origin = t.equals("[remote \"origin\"]");
                else if (origin && t.startsWith("url")) {
                    Matcher m = Pattern.compile("github\\.com[:/]([^/\\s]+)/([^/\\s]+?)(\\.git)?/?$").matcher(t);
                    if (m.find()) return m.group(1) + "/" + m.group(2);
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static String releaseBase() throws IOException {
        String base = cfgStr("release.base.url", "");
        if (!base.isEmpty()) return base.replaceAll("/+$", "");
        String repo = repo();
        if (repo == null) throw new IOException("不知道要從哪個 GitHub repo 下載，請在 config/map.properties 填 github.repo");
        return "https://github.com/" + repo + "/releases";
    }

    static String latestUrl() throws IOException {
        return releaseBase() + "/latest/download/release.properties";
    }

    static String assetUrl(String rel, String file) throws IOException {
        return releaseBase() + "/download/map-" + rel + "/" + file;
    }

    static String fetchText(String url) throws Exception {
        HttpResponse<String> r = web.send(get(url), HttpResponse.BodyHandlers.ofString(UTF_8));
        if (r.statusCode() == 404) throw new IOException("GitHub 上還沒有地圖版本，或 github.repo 填錯");
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return r.body();
    }

    static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "offline-map-service")
                .timeout(Duration.ofMinutes(2)).GET().build();
    }

    // ================================================================ 小工具

    static FileChannel lockChannel;
    static FileLock lock;

    static boolean acquireLock() throws IOException {
        lockChannel = FileChannel.open(logs.resolve("service.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        return lock != null;
    }

    static synchronized void log(String msg) {
        String line = "[" + LocalDateTime.now().format(TS) + "] " + msg;
        System.out.println(line);
        if (logFile != null) logFile.println(line);
    }

    static void rotate(Path p) {
        try {
            if (Files.exists(p) && Files.size(p) > 5L << 20) {
                Files.move(p, p.resolveSibling(p.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    static void unzip(Path zip, Path dest) throws IOException {
        Path base = dest.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                Path out = base.resolve(e.getName()).normalize();
                if (!out.startsWith(base)) throw new IOException("壓縮檔內容不合法：" + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void deleteQuietly(Path p) {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.delete(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log("注意：無法刪除 " + root.relativize(p) + "（" + brief(e) + "）");
        }
    }

    static void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + "：" + out.strip());
    }

    static Properties parse(String text) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(text));
        return p;
    }

    static String toText(Properties p) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(p).forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString();
    }

    static String cfgStr(String key, String def) {
        String v = cfg.getProperty(key, "").strip();
        return v.isEmpty() ? def : v;
    }

    static int cfgInt(String key, int def) {
        try {
            return Integer.parseInt(cfgStr(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static int port() { return cfgInt("port", 8989); }

    static int adminPort() { return cfgInt("admin.port", 8990); }

    static String mb(long bytes) { return (bytes >> 20) + " MB"; }

    static String brief(Throwable e) {
        if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                || e instanceof java.net.http.HttpConnectTimeoutException) return "連不上網路";
        if (e instanceof java.net.http.HttpTimeoutException) return "網路逾時";
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
