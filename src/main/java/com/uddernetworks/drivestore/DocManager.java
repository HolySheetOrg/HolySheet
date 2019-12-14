package com.uddernetworks.drivestore;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.Sheet;
import com.uddernetworks.drivestore.docs.ProgressListener;
import com.uddernetworks.drivestore.encoding.DecodingOutputStream;
import com.uddernetworks.drivestore.encoding.EncodingOutputStream;
import de.bwaldvogel.base91.Base91;
import de.bwaldvogel.base91.Base91OutputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.uddernetworks.drivestore.COptional.getCOptional;

public class DocManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocManager.class);

    private final DocStore docStore;
    private final Drive drive;
    private final Sheets sheets;

    private File docstore;

    public DocManager(DocStore docStore) {
        this.docStore = docStore;
        this.drive = docStore.getDrive();
        this.sheets = docStore.getSheets();
    }

    public void init() {
        try {
            LOGGER.info("Finding docstore folder...");

            docstore = getCOptional(getFiles(1, "name = 'docstore'", Mime.FOLDER)).orElseGet(() -> {
                try {
                    return drive.files().create(new File()
                            .setMimeType(Mime.FOLDER.getMime())
                            .setName("docstore")).execute();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            LOGGER.info("docstore id: {}", docstore.getId());
        } catch (IOException e) {
            LOGGER.error("An error occurred while finding or creating docstore directory", e);
        }

        LOGGER.info("Done!");
    }

    public File uploadSheet(String title, byte[] data) throws IOException {
        // Base91 stream writes to Encoding, which then writes to ByteArray
        var byteArrayOutputStream = new ByteArrayOutputStream();
        var encodingOutputStream = new EncodingOutputStream(byteArrayOutputStream);
        var base91OutputStream = new Base91OutputStream(encodingOutputStream);
        base91OutputStream.write(data);
        base91OutputStream.flush();

        var encoded = byteArrayOutputStream.toByteArray();

        var content = new ByteArrayContent("text/tab-separated-values", encoded);
        var request = drive.files().create(new File()
                .setMimeType(Mime.SHEET.getMime())
                .setName(title.replace(".", ". "))
                .setParents(Collections.singletonList(docstore.getId())), content);
        request.getMediaHttpUploader().setProgressListener(new ProgressListener("Upload"));
        return request.execute();
    }

    public DownloadedSheet download(String id) throws IOException {
        var byteOut = new ByteArrayOutputStream();
        var encodingOut = new DecodingOutputStream(byteOut);
        var file = drive.files().get(id).execute();
        drive.files().export(id, "text/tab-separated-values").executeMediaAndDownloadTo(encodingOut);

        return new DownloadedSheet(file, Base91.decode(byteOut.toByteArray())); // TODO: Integrate this with the EncodingOutputStream
    }

    public Optional<String> getIdOfName(String name) {
        try {
            return getCOptional(getFiles(-1, "parents in '" + docstore.getId() + "' and name contains '" + name.replace("'", "") + "'", Mime.DOCUMENT)).map(File::getId);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public List<File> listUploads() {
        try {
            return getSheets();
        } catch (IOException e) {
            LOGGER.error("An error occurred while listing uploads", e);
            return Collections.emptyList();
        }
    }

    private List<File> getSheets() throws IOException {
        return getFiles(-1, "parents in '" + docstore.getId() + "'", Mime.SHEET);
    }

    /**
     * Gets the files in the google drive with the given limit and matching mime types.
     *
     * @param limit The limit of files to find (-1 for all files, USE SPARINGLY)
     * @param mimes The mime types to match for
     * @return The list of files
     * @throws IOException
     */
    public List<File> getFiles(int limit, Mime... mimes) throws IOException {
        return getFiles(limit, null, mimes);
    }

    /**
     * Gets the files in the google drive with the given limit and matching mime types.
     *
     * @param limit The limit of files to find (-1 for all files, USE SPARINGLY)
     * @param query An additional query to search for
     * @param mimes The mime types to match for
     * @return The list of files
     * @throws IOException
     */
    public List<File> getFiles(int limit, String query, Mime... mimes) throws IOException {
        limit = Math.min(-1, limit);
        var mimeTypes = Arrays.stream(mimes).map(Mime::getMime).collect(Collectors.toUnmodifiableSet());
        var foundFiles = new ArrayList<File>();

        var pageToken = "";
        do {
            var result = getPagesFiles(pageToken, 50, mimes, query);
            pageToken = result.getNextPageToken();
            var files = result.getFiles();
            if (files == null || files.isEmpty()) {
                break;
            }

            for (var file : files) {
                if (!mimeTypes.contains(file.getMimeType())) {
                    continue;
                }

                foundFiles.add(file);

                if (--limit == 0) {
                    return foundFiles;
                }
            }
        } while (pageToken != null);

        return foundFiles;
    }

    private FileList getPagesFiles(String pageToken, int pageSize, Mime[] mimes, String query) throws IOException {
        var builder = drive.files().list()
                .setPageSize(pageSize)
                .setFields("nextPageToken, files(id, name, mimeType, parents, size, modifiedTime)");

        if (pageToken != null && !pageToken.isBlank()) {
            builder.setPageToken(pageToken);
        }

        var q = getQueryFromMime(mimes).orElse("");

        if (query != null && !query.isBlank()) {
            if (q.isBlank()) {
                q = query;
            } else {
                q += " and " + query;
            }
        }

        builder.setQ(q);

        return builder.execute();
    }

    private Optional<String> getQueryFromMime(Mime[] mimes) {
        if (mimes.length == 0) return Optional.empty();
        var base = new StringBuilder();
        for (var mime : mimes) {
            base.append("mimeType = '").append(mime.getMime()).append("' or ");
        }

        var q = base.substring(0, base.length() - 4);
        return Optional.of(q);
    }
}
