//package com.uddernetworks.drivestore;
//
//import com.uddernetworks.drivestore.console.ConsoleTableBuilder;
//import org.apache.commons.io.FilenameUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.apache.commons.lang3.math.NumberUtils;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.text.SimpleDateFormat;
//import java.util.ArrayList;
//import java.util.Date;
//import java.util.List;
//import java.util.regex.Pattern;
//
//import static com.uddernetworks.drivestore.utility.Utility.humanReadableByteCountSI;
//
//public class CommandHandler {
//
//    private static final Logger LOGGER = LoggerFactory.getLogger(CommandHandler.class);
//    private static final Pattern ID_PATTERN = Pattern.compile("([a-zA-Z0-9-_]+)");
//
//    /**
//     * Parses given arguments.
//     *
//     * @param args     The CLI arguments
//     * @param docStore The DocStore
//     * @param init     Initializes authentication and everything before accessing data
//     */
//    public void parseCommand(String[] args, DocStore docStore, Runnable init) {
//        Options options = new Options();
//
//        options.addOption(new Option("h", "help", false, "Shows help"));
//
//        var opt = new Option("l", "list", true, "List the uploaded documents");
//        opt.setOptionalArg(true);
//        opt.setArgName("query");
//        options.addOption(opt);
//
//        var upload = new Option("u", "upload", true, "Uploads the local given file");
//        upload.setArgName("file");
//        upload.setType(File.class);
//        options.addOption(upload);
//
//        var download = new Option("d", "download", true, "Downloads the given ID or name from Google Drive");
//        download.setArgName("id/name");
//        options.addOption(download);
//
//        var delete = new Option("r", "remove", true, "Removes the given ID or name from Google Docs");
//        delete.setArgName("id/name");
//        options.addOption(delete);
//
//        CommandLineParser parser = new DefaultParser();
//        HelpFormatter formatter = new HelpFormatter();
//
//        try {
//            var cmd = parser.parse(options, args);
//            if (cmd.hasOption("help")) {
//                formatter.printHelp("DocStore", options);
//            } else if (cmd.hasOption("list")) {
//                init.run();
//
//                var docManager = docStore.getSheetManager();
//
//                var table = new ConsoleTableBuilder()
//                        .addColumn("Name", 20)
//                        .addColumn("Size", 8)
//                        .addColumn("Sheets", 6)
//                        .addColumn("Date", 10)
//                        .addColumn("Id", 33)
//                        .setHorizontalSpacing(3);
//
//                String pattern = "MM-dd-yyyy";
//                var dateFormat = new SimpleDateFormat(pattern);
//
//                var rows = new ArrayList<List<String>>();
//                docManager.listUploads().forEach(file -> {
//                    rows.add(List.of(
//                            file.getName(),
//                            humanReadableByteCountSI(Long.parseLong(file.getProperties().get("size"))),
//                            getSheetCount(file),
//                            dateFormat.format(new Date(file.getModifiedTime().getValue())),
//                            file.getId()
//                    ));
//                });
//                System.out.println("\n");
//                System.out.println(table.generateTable(rows));
//            } else if (cmd.hasOption("upload")) {
//                var file = (File) cmd.getOptionObject('u');
//                if (!file.isFile()) {
//                    LOGGER.error("File '{}' does not exist!", file.getAbsolutePath());
//                    return;
//                }
//
//                init.run();
//
//                var sheetIO = docStore.getSheetManager().getSheetIO();
//
//                try {
//                    long start = System.currentTimeMillis();
//                    var name = FilenameUtils.getName(file.getAbsolutePath());
////                    docStore.getSheetManager().uploadData(name, new FileInputStream(file)).ifPresentOrElse(id -> {
////                        LOGGER.info("Uploaded {} with ID of: {}", name, GREEN + id + RESET);
////                    }, () -> {
////                        LOGGER.error("Couldn't upload file");
////                    });
//
//                    var ups = sheetIO.uploadData(name, new FileInputStream(file).readAllBytes());
////                    var ups = sheetIO.uploadData(name, "This is a test of some bullshit idk if this will work but it uses chunks instead of a single file haha".getBytes());
//
//                    LOGGER.info("Uploaded {} in {}ms", ups.getId(), System.currentTimeMillis() - start);
//                } catch (IOException e) {
//                    LOGGER.error("Error reading and uploading file", e);
//                }
//            } else if (cmd.hasOption("download")) {
//                init.run();
//
//                var docManager = docStore.getSheetManager();
//                var idName = cmd.getOptionValue("download");
//
//                if (ID_PATTERN.matcher(idName).matches()) {
//                    idName = docManager.getIdOfName(idName).orElse(idName);
//                }
//
//                long start = System.currentTimeMillis();
//                var sheet = docManager.getFile(idName);
//
//                if (sheet == null) {
//                    LOGGER.info("Couldn't find file with id/name of {}", idName);
//                    return;
//                }
//
//                try (var fileStream = new FileOutputStream(sheet.getName())) {
//                    var byteOptional = docManager.getSheetIO().downloadData(idName);
//                    if (byteOptional.isEmpty()) {
//                        LOGGER.error("An error occurred while downloading {}", idName);
//                        return;
//                    }
//
//                    fileStream.write(byteOptional.get().toByteArray());
//                }
//
//                LOGGER.info("Downloaded in {}ms", System.currentTimeMillis() - start);
//            } else {
//                formatter.printHelp("DocStore", options);
//            }
//        } catch (ParseException | IOException e) {
////            System.err.println(e.getMessage());
//            LOGGER.error("Error", e);
//            formatter.printHelp("DocStore", options);
//        }
//    }
//
//    private String getSheetCount(com.google.api.services.drive.model.File file) {
//        var string = file.getProperties().get("sheets");
//        if (!StringUtils.isNumeric(string)) {
//            return "0";
//        }
//
//        return String.valueOf(Integer.parseInt(string));
//    }
//
//}
