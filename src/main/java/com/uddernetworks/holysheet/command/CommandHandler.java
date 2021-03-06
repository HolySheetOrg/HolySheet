package com.uddernetworks.holysheet.command;

import com.google.api.services.drive.model.User;
import com.uddernetworks.grpc.HolysheetService;
import com.uddernetworks.holysheet.HolySheet;
import com.uddernetworks.holysheet.SheetManager;
import com.uddernetworks.holysheet.console.ConsoleTableBuilder;
import com.uddernetworks.holysheet.io.SheetIO;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.uddernetworks.grpc.HolysheetService.UploadRequest.Compression.*;
import static com.uddernetworks.grpc.HolysheetService.UploadRequest.Upload.MULTIPART;
import static com.uddernetworks.holysheet.utility.Utility.humanReadableByteCountSI;

@CommandLine.Command(name = "example", mixinStandardHelpOptions = true, version = "DriveStore 1.0.0", customSynopsis = {
        "([-cm] -u=<file>... | [-cm] -e=<id> | -d=<name/id>... |  -r=<name/id>...) [-agphlzV]"
})
public class CommandHandler implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandHandler.class);
    public static final DateFormat DATE_FORMAT = new SimpleDateFormat("MM-dd-yyyy");
    public static final Pattern ID_PATTERN = Pattern.compile("([a-zA-Z0-9-_]+)");

    private final HolySheet holySheet;
    private SheetManager sheetManager;
    private SheetIO sheetIO;

    @Option(names = {"-l", "--list"}, description = "Lists the uploaded files in Google Sheets")
    boolean list;

    @Option(names = {"-a", "--credentials"}, description = "The (absolute or relative) location of your personal credentials.json file. If no file extension is found, it is assumed to be an environment variable")
    String credentials = "credentials.json";

    @Option(names = {"-z", "--local-auth"}, description = "If the authentication should take place on the local machine")
    boolean localAuth;

    @Option(names = {"-g", "--grpc"}, description = "Starts the gRPC server on the given port, used to interface with other apps")
    int grpc = -1;

    @Option(names = {"-p", "--parent"}, description = "Kills the process (When running with socket) when the given PID is killed")
    int parent = -1;

    @Option(names = {"-c", "--compress"}, description = "Compressed before uploading, currently uses Zip format")
    boolean compression;

    @Option(names = {"-m", "--sheetSize"}, defaultValue = "10000000", description = "The maximum size in bytes a single sheet can be. Defaults to 10MB")
    int sheetSize;

    @ArgGroup(multiplicity = "0..1")
    RequiresParam param;

    public CommandHandler(HolySheet holySheet) {
        this.holySheet = holySheet;
    }

    static class RequiresParam {

        @Option(names = {"-d", "--download"}, arity = "1..*", description = "Download the remote file", paramLabel = "<id/name>")
        String[] download;

        @Option(names = {"-r", "--remove"}, arity = "1..*", description = "Permanently removes the remote file", paramLabel = "<id/name>")
        List<String> remove;

        @Option(names = {"-e", "--clone"}, arity = "1..*", description = "Clones the remote file ID to Google Sheets", paramLabel = "<id/name>")
        List<String> clone;

        @Option(names = {"-u", "--upload"}, arity = "1..*", description = "Upload the local file", paramLabel = "<file>")
        File[] upload;
    }

    @Override
    public void run() {
        suicideForParent(parent);

        if (grpc > 0) {
            holySheet.init(localAuth ? credentials : null);
            holySheet.getGrpcClient().start(grpc);
            return;
        }

        holySheet.init(credentials);
        var authManager = holySheet.getAuthManager();
        sheetManager = new SheetManager(authManager.getDrive(), authManager.getSheets());
        sheetIO = sheetManager.getSheetIO();

        if (list) {
            list();
            return;
        }

        if (param.upload != null) {
            upload();
            return;
        }

        if (param.download != null) {
            download();
            return;
        }

        if (param.remove != null) {
            remove();
            return;
        }

        if (param.clone != null) {
            cloneFiles();
            return;
        }
    }

    private void list() {
        var table = new ConsoleTableBuilder()
                .addColumn("Name", 20)
                .addColumn("Size", 8)
                .addColumn("Sheets", 6)
                .addColumn("Owner", 20)
                .addColumn("Date", 10)
                .addColumn("Id", 33)
                .setHorizontalSpacing(3);

        var uploads = sheetManager.listUploads();

        System.out.println("\n");
        System.out.println(table.generateTable(uploads
                .stream()
                .map(file -> List.of(
                        file.getName(),
                        humanReadableByteCountSI(Long.parseLong(file.getProperties().get("size"))),
                        String.valueOf(getSheetCount(file)),
                        file.getOwners().stream().map(User::getDisplayName).collect(Collectors.joining(",")),
                        DATE_FORMAT.format(new Date(file.getModifiedTime().getValue())),
                        file.getId()
                )).collect(Collectors.toList()), List.of(
                "Total",
                humanReadableByteCountSI(uploads.stream().mapToLong(file -> Long.parseLong(file.getProperties().get("size"))).sum()),
                String.valueOf(uploads.stream().mapToInt(CommandHandler::getSheetCount).sum()),
                "",
                "",
                ""
        )));
    }

    private void upload() {
        long start = System.currentTimeMillis();
        var upload = param.upload;
        for (var file : upload) {
            uploadFile(file);
        }
        LOGGER.info("Finished the uploading of {} file{} in {}ms", upload.length, upload.length == 1 ? "" : "s", System.currentTimeMillis() - start);
    }

    private void uploadFile(File file) {
        if (!file.isFile()) {
            LOGGER.error("File '{}' does not exist!", file.getAbsolutePath());
            return;
        }

        LOGGER.info("Uploading {}...", file.getName());

        try {
            long start = System.currentTimeMillis();
            var name = FilenameUtils.getName(file.getAbsolutePath());

            var ups = sheetIO.uploadDataFile(name, "/", file.length(), sheetSize, compression ? ZIP : NONE, MULTIPART, new FileInputStream(file));

            LOGGER.info("Uploaded {} in {}ms", ups.getId(), System.currentTimeMillis() - start);
        } catch (IOException e) {
            LOGGER.error("Error reading and uploading file", e);
        }
    }

    private void download() {
        ForkJoinPool.commonPool().invokeAll(Arrays
                .stream(param.download)
                .map(this::downloadIdName)
                .map(f -> (Callable<Void>) f::join).collect(Collectors.toList()));
    }

    private CompletableFuture<Void> downloadIdName(String idName) {
        try {
            if (!ID_PATTERN.matcher(idName).matches()) {
                idName = sheetManager.getIdOfName(idName).orElse(idName);
            }

            long start = System.currentTimeMillis();
            var sheet = sheetManager.getFile(idName);

            if (sheet == null) { // probably won't return null, will just throw
                LOGGER.info("Couldn't find file with id/name of {}", idName);
                return CompletableFuture.completedFuture(null);
            }

            return sheetIO.downloadData(new File(sheet.getName()), idName).exceptionally(t -> {
                LOGGER.error("An error occurred while downloading file", t);
                return null;
            }).thenAccept($ -> LOGGER.info("Downloaded in {}ms", System.currentTimeMillis() - start));
        } catch (IOException e) {
            LOGGER.error("An error occurred while downloading file " + idName, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private void remove() {
        param.remove.forEach(idName -> {
            try {
                if (!ID_PATTERN.matcher(idName).matches()) {
                    idName = sheetManager.getIdOfName(idName).orElse(idName);
                }

                sheetIO.deleteData(idName, false, false);
            } catch (IOException e) {
                LOGGER.error("An error has occurred while deleting the file " + idName, e);
            }
        });
    }

    private void cloneFiles() {
        for (String idName : param.clone) {
            if (!ID_PATTERN.matcher(idName).matches()) {
                idName = sheetManager.getIdOfName(idName, false).orElse(idName);
                // false parameter - as it's not a sheet! cloning google drive documents.
            }

            var method = compression ? ZIP : NONE;
            sheetIO.cloneFile(idName, sheetSize, method);
        }
    }

    private void suicideForParent(int parent) {
        if (parent == -1) {
            return;
        }

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            var processOptional = ProcessHandle.of(parent);
            if (processOptional.isEmpty()) {
                LOGGER.info("No PID found of {}. Terminating...", parent);
                System.exit(0);
            }
        }, 1, 3, TimeUnit.SECONDS);
    }

    public static int getSheetCount(com.google.api.services.drive.model.File file) {
        var string = file.getProperties().get("sheets");
        if (!StringUtils.isNumeric(string)) {
            return 0;
        }

        return Integer.parseInt(string);
    }

    public static String getPath(com.google.api.services.drive.model.File file) {
        var path = file.getProperties().get("path");
        if (path == null) {
            return "";
        }

        return path;
    }

    public static boolean isStarred(com.google.api.services.drive.model.File file) {
        var string = file.getProperties().get("starred");
        return string != null && string.equals("true");
    }

    public static long getSize(com.google.api.services.drive.model.File file) {
        var string = file.getProperties().get("size");
        if (!StringUtils.isNumeric(string)) {
            return 0;
        }

        return Long.parseLong(string);
    }
}
