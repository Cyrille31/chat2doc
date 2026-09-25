/*
 * Chat2Doc — CGExcel
 * Convertit une discussion WhatsApp exportée en document Word, avec les médias rangés à côté.
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 */
package fr.cgexcel.chat2doc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.cgexcel.chat2doc.core.Archive;
import fr.cgexcel.chat2doc.core.ChatParser;
import fr.cgexcel.chat2doc.core.Converter;
import fr.cgexcel.chat2doc.core.DocxWriter;
import fr.cgexcel.chat2doc.core.LinkPreviewFetcher;
import fr.cgexcel.chat2doc.core.ProgressListener;
import fr.cgexcel.chat2doc.core.Zips;

public class MainActivity extends Activity {

    private static final int REQ_PICK_ZIP = 1;
    private static final int REQ_SAVE = 2;
    private static final int REQ_FOLDER = 3;
    private static final String PREFS = "chat2doc";
    private static final String PREF_QUALITY = "qualite_photos";
    private static final String PREF_SPLIT = "un_document_par_annee";
    private static final int[] QUALITY_PX = {800, 1280, 0};
    private static final String GITHUB = "https://github.com/Cyrille31/chat2doc";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View home, working, done;
    private TextView stage, progressDetail, summary, error;
    private ProgressBar progress;
    private RadioGroup quality;
    private CheckBox splitYear;
    private Button cancelButton, folderChoose, folderForget, saveButton;
    private TextView folderStatus, savedTitle;
    private LinearLayout savedList;

    private volatile boolean cancelled;
    /** Récupération des aperçus en cours, et demande de l'utilisateur d'ignorer les aperçus restants. */
    private volatile boolean fetchingPreviews, skipPreviews;
    private static boolean previewsRequested;
    /** Nom du dossier Chat2Doc mis à jour par la dernière conversion, ou {@code null}. */
    private static volatile String libraryName;
    private boolean busy;
    private Converter.Result result;

    // ================================================================================================
    // Cycle de vie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        home = findViewById(R.id.home);
        working = findViewById(R.id.working);
        done = findViewById(R.id.done);
        stage = findViewById(R.id.stage);
        progressDetail = findViewById(R.id.progress_detail);
        summary = findViewById(R.id.summary);
        error = findViewById(R.id.error);
        progress = findViewById(R.id.progress);
        quality = findViewById(R.id.quality);
        splitYear = findViewById(R.id.split_year);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int q = prefs.getInt(PREF_QUALITY, 1);
        quality.check(q == 0 ? R.id.quality_small : q == 2 ? R.id.quality_original : R.id.quality_standard);
        quality.setOnCheckedChangeListener((group, id) -> prefs.edit().putInt(PREF_QUALITY,
                id == R.id.quality_small ? 0 : id == R.id.quality_original ? 2 : 1).apply());
        splitYear.setChecked(prefs.getBoolean(PREF_SPLIT, true));
        splitYear.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(PREF_SPLIT, checked).apply());

        findViewById(R.id.pick_zip).setOnClickListener(v -> pickZip());
        cancelButton = findViewById(R.id.cancel);
        cancelButton.setOnClickListener(v -> {
            if (fetchingPreviews) {
                skipPreviews = true;
                stage.setText("Aperçus restants ignorés, suite de la conversion…");
            } else {
                cancelled = true;
                stage.setText("Annulation…");
            }
        });
        saveButton = findViewById(R.id.save);
        saveButton.setOnClickListener(v -> save());
        folderStatus = findViewById(R.id.folder_status);
        folderChoose = findViewById(R.id.folder_choose);
        folderForget = findViewById(R.id.folder_forget);
        savedTitle = findViewById(R.id.saved_title);
        savedList = findViewById(R.id.saved_list);
        folderChoose.setOnClickListener(v -> chooseFolder());
        folderForget.setOnClickListener(v -> forgetFolder());
        findViewById(R.id.share).setOnClickListener(v -> share());
        findViewById(R.id.open_word).setOnClickListener(v -> openWord());
        findViewById(R.id.again).setOnClickListener(v -> showHome());
        findViewById(R.id.footer).setOnClickListener(v -> showLicence());

        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (busy) {
            Toast.makeText(this, "Une conversion est déjà en cours.", Toast.LENGTH_LONG).show();
        } else {
            handleIntent(intent);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (busy) {
            Toast.makeText(this, "Conversion en cours : touchez « Annuler » pour l’interrompre.", Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        worker.shutdown();
        super.onDestroy();
    }

    // ================================================================================================
    // Réception du partage

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)
                && !Intent.ACTION_VIEW.equals(action)) {
            showHome();
            return;
        }
        List<Uri> uris = collectUris(intent);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);

        if (uris.isEmpty() && (text == null || !ChatParser.looksLikeChat(text))) {
            showHome();
            showError("Ce partage ne contient pas d’export WhatsApp.\n\nDans WhatsApp : ouvrez la discussion, "
                    + "menu ⋮ → Plus → Exporter la discussion, puis choisissez Chat2Doc.");
            return;
        }
        convert(uris, uris.isEmpty() ? text : null, subject);
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> collectUris(Intent intent) {
        Set<Uri> set = new LinkedHashSet<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) set.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null) set.add(u);
        } else if (intent.getData() != null) {
            set.add(intent.getData());
        }
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) set.add(u);
            }
        }
        return new ArrayList<>(set);
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed",
                "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_PICK_ZIP);
        } catch (ActivityNotFoundException e) {
            showError("Aucun sélecteur de fichiers n’est disponible sur ce téléphone.");
        }
    }

    // ================================================================================================
    // Conversion

    private void convert(List<Uri> uris, String sharedText, String subject) {
        busy = true;
        cancelled = false;
        result = null;
        showWorking();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final int px = QUALITY_PX[qualityIndex()];
        final boolean split = splitYear.isChecked();
        final ProgressListener listener = new UiProgress();

        worker.execute(() -> {
            try {
                File jobs = new File(getCacheDir(), "jobs");
                File previousJob = keepLatestJob(jobs);
                File job = new File(jobs, String.valueOf(System.currentTimeMillis()));
                File in = new File(job, "entree"), work = new File(job, "archive"), tmp = new File(job, "tmp");
                if (!in.mkdirs() || !work.mkdirs() || !tmp.mkdirs()) throw new IOException("Espace de travail indisponible.");

                String hint = subject;
                if (sharedText != null) {
                    try (OutputStream os = new FileOutputStream(new File(in, "Discussion WhatsApp.txt"))) {
                        os.write(sharedText.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    String zipName = receive(uris, in, listener);
                    if (hint == null) hint = zipName;
                }

                Converter.Options opt = new Converter.Options();
                opt.imageMaxPx = px;
                opt.splitByYear = split;
                opt.titleHint = hint;
                opt.dayFirstByDefault = !Locale.getDefault().getCountry().equals("US");
                Converter.Inspection ins = Converter.inspect(in, opt);

                Library lib = Library.open(this);
                if (lib != null) {
                    // Dossier Chat2Doc : la discussion y est complétée si elle existe déjà
                    opt.makeZip = false;
                    if (previousJob != null) Zips.deleteRecursively(previousJob);
                    Archive archive = lib.checkout(ins.safe, new File(work, ins.safe), listener);
                    prepareThen(ins, archive, lib, in, tmp, opt, listener);
                } else {
                    // Sans dossier : fusion possible avec la conversion précédente de la même discussion
                    File prevArchive = previousJob == null ? null : new File(new File(previousJob, "archive"), ins.safe);
                    if (prevArchive != null && new File(prevArchive, Archive.TEXTS).isDirectory()) {
                        ui.post(() -> askMerge(ins, prevArchive, previousJob, work, in, tmp, opt, listener));
                    } else {
                        if (previousJob != null) Zips.deleteRecursively(previousJob);
                        prepareThen(ins, new Archive(new File(work, ins.safe), null), null, in, tmp, opt, listener);
                    }
                }
            } catch (ProgressListener.CancelledException e) {
                ui.post(() -> finished(null, "Conversion annulée."));
            } catch (Throwable t) {
                ui.post(() -> finished(null, describe(t)));
            }
        });
    }

    /** Supprime les anciennes conversions, sauf la dernière (qui peut servir à une fusion) ; la renvoie. */
    private static File keepLatestJob(File jobs) {
        File[] list = jobs.listFiles();
        if (list == null || list.length == 0) return null;
        java.util.Arrays.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
        for (int k = 0; k < list.length - 1; k++) Zips.deleteRecursively(list[k]);
        return list[list.length - 1];
    }

    private void askMerge(Converter.Inspection ins, File prevArchive, File previousJob, File work, File in, File tmp,
                          Converter.Options opt, ProgressListener listener) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("Fusionner les exports ?")
                .setMessage("Vous venez de convertir la discussion « " + ins.title + " ».\n\n"
                        + "Faut-il y ajouter ce nouvel export ? C’est utile pour réunir un export « sans les médias » "
                        + "(qui remonte plus loin) et un export « avec les médias » (messages récents avec photos) : "
                        + "les messages communs ne sont pas dupliqués.")
                .setCancelable(false)
                .setPositiveButton("Fusionner", (d, w) -> worker.execute(() -> mergeThen(true, ins, prevArchive,
                        previousJob, work, in, tmp, opt, listener)))
                .setNegativeButton("Conversion séparée", (d, w) -> worker.execute(() -> mergeThen(false, ins,
                        prevArchive, previousJob, work, in, tmp, opt, listener)))
                .show();
    }

    private void mergeThen(boolean merge, Converter.Inspection ins, File prevArchive, File previousJob, File work,
                           File in, File tmp, Converter.Options opt, ProgressListener listener) {
        try {
            File dest = new File(work, ins.safe);
            if (merge && !prevArchive.renameTo(dest)) throw new IOException("Fusion impossible (espace de travail).");
            Zips.deleteRecursively(previousJob);
            prepareThen(ins, new Archive(dest, null), null, in, tmp, opt, listener);
        } catch (ProgressListener.CancelledException e) {
            ui.post(() -> finished(null, "Conversion annulée."));
        } catch (Throwable t) {
            ui.post(() -> finished(null, describe(t)));
        }
    }

    /** Sur le fil de travail : analyse, puis question sur les aperçus. */
    private void prepareThen(Converter.Inspection ins, Archive archive, Library lib, File in, File tmp,
                             Converter.Options opt, ProgressListener listener) throws IOException {
        Converter.Prepared prepared = Converter.prepare(ins, archive, tmp, opt, listener);
        Zips.deleteRecursively(in);
        ui.post(() -> askPreviews(prepared, lib, tmp, listener));
    }

    /** Discussion lue : s'il y a des liens nouveaux, on propose d'aller chercher leurs aperçus, avec une estimation de durée. */
    private void askPreviews(Converter.Prepared p, Library lib, File tmp, ProgressListener listener) {
        previewsRequested = false;
        if (p.linksToFetch.isEmpty() || isFinishing() || isDestroyed()) {
            finishConversion(p, lib, false, tmp, listener);
            return;
        }
        int n = p.linksToFetch.size();
        int known = p.links.size() - n;
        StringBuilder msg = new StringBuilder();
        msg.append(known > 0 ? "Cette discussion contient " + count(n, "nouveau lien", "nouveaux liens")
                : "Cette discussion contient " + count(n, "lien", "liens")).append(" vers des sites web");
        if (p.youtubeLinks > 0) {
            msg.append(p.youtubeLinks == n ? " (" + (n > 1 ? "toutes des vidéos" : "une vidéo") + " YouTube)"
                    : ", dont " + count(p.youtubeLinks, "vidéo", "vidéos") + " YouTube");
        }
        msg.append(".");
        if (known > 0) msg.append(" Les aperçus des ").append(count(known, "autre lien sont", "autres liens sont"))
                .append(" déjà connus.");
        msg.append("\n\nChat2Doc peut récupérer sur Internet leur aperçu (titre et image), comme dans WhatsApp.\n\n")
                .append("Durée estimée : ").append(LinkPreviewFetcher.describeDuration(LinkPreviewFetcher.estimateSeconds(n)))
                .append(" (selon la connexion ; le Wi-Fi est conseillé).\n\n")
                .append("Sans aperçus, les liens restent cliquables dans le document.");
        new AlertDialog.Builder(this)
                .setTitle("Aperçus des liens")
                .setMessage(msg.toString())
                .setCancelable(false)
                .setPositiveButton("Récupérer les aperçus", (d, w) -> finishConversion(p, lib, true, tmp, listener))
                .setNegativeButton("Continuer sans", (d, w) -> finishConversion(p, lib, false, tmp, listener))
                .show();
    }

    private void finishConversion(Converter.Prepared p, Library lib, boolean withPreviews, File tmp,
                                  ProgressListener listener) {
        previewsRequested = withPreviews;
        skipPreviews = false;
        worker.execute(() -> {
            try {
                Map<String, LinkPreviewFetcher.Preview> previews = null;
                Set<String> failed = null;
                if (withPreviews) {
                    fetchingPreviews = true;
                    ui.post(() -> cancelButton.setText("Ignorer les aperçus restants"));
                    failed = ConcurrentHashMap.newKeySet();
                    try {
                        previews = new LinkPreviewFetcher().fetchAll(p.linksToFetch, new File(tmp, "apercus"), listener,
                                () -> skipPreviews, failed);
                    } finally {
                        fetchingPreviews = false;
                        ui.post(() -> cancelButton.setText(R.string.cancel));
                    }
                }
                Converter.Result r = Converter.finish(p, previews, failed, new AndroidImageProcessor(), listener);
                libraryName = null;
                if (lib != null) {
                    lib.commit(r.archive, r.folder.getName(), listener);
                    libraryName = lib.name();
                }
                ui.post(() -> finished(r, null));
            } catch (ProgressListener.CancelledException e) {
                ui.post(() -> finished(null, "Conversion annulée."));
            } catch (Throwable t) {
                ui.post(() -> finished(null, describe(t)));
            }
        });
    }

    /** Copie les fichiers partagés dans {@code dir} (en décompressant les .zip). Renvoie le nom du .zip reçu, le cas échéant. */
    private String receive(List<Uri> uris, File dir, ProgressListener listener) throws IOException {
        String zipName = null;
        int n = 0;
        for (Uri uri : uris) {
            if (listener.isCancelled()) throw new ProgressListener.CancelledException();
            listener.onProgress("Réception des fichiers", n++, uris.size());
            String name = displayName(uri);
            String type = getContentResolver().getType(uri);
            boolean zip = name.toLowerCase(Locale.ROOT).endsWith(".zip")
                    || "application/zip".equals(type) || "application/x-zip-compressed".equals(type);
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) continue;
                if (zip) {
                    Zips.unzip(new BufferedInputStream(is, 1 << 16), dir, listener);
                    zipName = name;
                } else {
                    File dest = unique(dir, name);
                    try (OutputStream os = new FileOutputStream(dest)) {
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
                    }
                }
            } catch (SecurityException e) {
                throw new IOException("L’accès à un fichier partagé a été refusé. Relancez l’export depuis WhatsApp.");
            }
        }
        listener.onProgress("Réception des fichiers", uris.size(), uris.size());
        return zipName;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (RuntimeException ignored) {
            // certains fournisseurs ne répondent pas à la requête : on se rabat sur l'adresse
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "fichier";
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\\\:*?\"<>|]", "_");
        return name.isEmpty() ? "fichier" : name;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    private void finished(Converter.Result r, String message) {
        busy = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (isFinishing() || isDestroyed()) return;
        if (r == null) {
            showHome();
            showError(message);
            return;
        }
        result = r;
        summary.setText(describe(r));
        saveButton.setVisibility(r.zip != null ? View.VISIBLE : View.GONE);
        home.setVisibility(View.GONE);
        working.setVisibility(View.GONE);
        done.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
    }

    private static String describe(Converter.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("« ").append(r.title).append(" »\n");
        sb.append(count(r.stats.messages, "message", "messages")).append(" de ")
                .append(count(r.stats.perSender.size(), "participant", "participants"));
        if (r.stats.first != null) {
            if (r.stats.first.toLocalDate().equals(r.stats.last.toLocalDate())) {
                sb.append(", le ").append(DocxWriter.dateFr(r.stats.first.toLocalDate(), false));
            } else {
                sb.append(", du ").append(DocxWriter.dateFr(r.stats.first.toLocalDate(), false))
                        .append(" au ").append(DocxWriter.dateFr(r.stats.last.toLocalDate(), false));
            }
        }
        sb.append(".");
        if (r.update) {
            sb.append("\n").append(r.newMessages > 0 ? "Mise à jour : " + count(r.newMessages, "nouveau message", "nouveaux messages")
                    : "Aucun nouveau message depuis la dernière fois")
                    .append(r.exports > 1 ? " (" + count(r.exports, "export", "exports") + " réunis)." : ".");
        }
        sb.append("\n\n");
        if (r.volumes.size() > 1) {
            List<String> years = new ArrayList<>();
            for (String v : r.rewritten) years.add(v.replaceAll("^.* - (\\d{4})\\.docx$", "$1"));
            sb.append(r.volumes.size()).append(" documents Word, un par année");
            if (r.update && !years.isEmpty() && years.size() < r.volumes.size()) {
                sb.append(" (mis à jour : ").append(String.join(", ", years)).append(")");
            }
            sb.append(".\n");
        }
        sb.append(count(r.embeddedPictures, "photo insérée", "photos insérées")).append(" dans ")
                .append(r.volumes.size() > 1 ? "les documents Word, " : "le document Word, ")
                .append(count(r.mediaFiles, "média rangé", "médias rangés")).append(" dans l’archive.");
        if (!r.stats.missing.isEmpty()) {
            sb.append("\n").append(count(r.stats.missing.size(), "fichier cité est absent", "fichiers cités sont absents"))
                    .append(" de l’export.");
        }
        if (r.stats.omitted > 0) {
            sb.append("\n").append(count(r.stats.omitted, "média n’a pas été fourni", "médias n’ont pas été fournis"))
                    .append(" par WhatsApp (export sans les médias ?).");
        }
        if (r.links > 0) {
            sb.append("\n").append(count(r.links, "lien", "liens"));
            if (r.previews > 0) sb.append(", dont ").append(count(r.previews, "avec aperçu", "avec aperçu"));
            else if (previewsRequested) sb.append(" : aucun aperçu obtenu (pas de connexion Internet ?)");
            sb.append(".");
        }
        if (libraryName != null && r.zip == null) {
            sb.append("\n\nEnregistré dans le dossier « ").append(libraryName).append(" » › ").append(r.folder.getName()).append(".");
        }
        if (r.zip != null) {
            sb.append("\n\nArchive : ").append(r.zip.getName()).append(" (").append(size(r.zip.length())).append(")");
        }
        if (r.warning != null) sb.append("\n\n⚠ ").append(r.warning);
        return sb.toString();
    }

    private static String describe(Throwable t) {
        String m = String.valueOf(t.getMessage());
        if (t instanceof OutOfMemoryError) {
            return "Mémoire insuffisante. Choisissez « Photos compactes » et recommencez.";
        }
        if (m.contains("ENOSPC") || m.toLowerCase(Locale.ROOT).contains("no space")) {
            return "Espace de stockage insuffisant sur le téléphone. Libérez de la place (il faut environ "
                    + "deux fois la taille de l’export) puis recommencez.";
        }
        return "La conversion a échoué.\n\n" + (t.getMessage() != null ? t.getMessage() : t.toString());
    }

    // ================================================================================================
    // Enregistrement, partage, ouverture

    private void save() {
        if (result == null || result.zip == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, result.zip.getName());
        try {
            startActivityForResult(i, REQ_SAVE);
        } catch (ActivityNotFoundException e) {
            share();
        }
    }

    private void saveTo(Uri target) {
        final File zip = result.zip;
        busy = true;
        cancelled = false;
        showWorking();
        final ProgressListener listener = new UiProgress();
        worker.execute(() -> {
            try (InputStream is = new FileInputStream(zip);
                 OutputStream os = getContentResolver().openOutputStream(target, "wt")) {
                if (os == null) throw new IOException("Emplacement inaccessible.");
                byte[] buf = new byte[1 << 16];
                long total = zip.length(), copied = 0;
                int r;
                while ((r = is.read(buf)) > 0) {
                    if (listener.isCancelled()) throw new ProgressListener.CancelledException();
                    os.write(buf, 0, r);
                    copied += r;
                    listener.onProgress("Enregistrement de l’archive", (int) (copied >> 10), (int) (total >> 10));
                }
                ui.post(() -> {
                    finished(result, null);
                    Toast.makeText(this, "Archive enregistrée.", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                final String msg = t instanceof ProgressListener.CancelledException
                        ? "Enregistrement annulé." : "Enregistrement impossible : " + t.getMessage();
                ui.post(() -> {
                    finished(result, null);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void share() {
        if (result == null) return;
        // Archive .zip en mode sans dossier ; document Word seul avec un dossier Chat2Doc
        File f = result.zip != null ? result.zip : result.docx;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fichiers", f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(result.zip != null ? "application/zip" : DOCX);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_SUBJECT, result.title + " — discussion WhatsApp");
        i.setClipData(ClipData.newRawUri(f.getName(), uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, getString(R.string.share)));
    }

    private void openWord() {
        if (result == null) return;
        openDocument(FileProvider.getUriForFile(this, getPackageName() + ".fichiers", result.docx));
    }

    private void openDocument(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, DOCX);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Aucune application ne sait ouvrir les documents Word sur ce téléphone "
                    + "(Word, Google Docs, WPS Office…).", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_PICK_ZIP) {
            convert(Collections.singletonList(data.getData()), null, null);
        } else if (requestCode == REQ_SAVE && result != null) {
            saveTo(data.getData());
        } else if (requestCode == REQ_FOLDER) {
            try {
                Library.choose(this, data.getData());
                Toast.makeText(this, "Dossier Chat2Doc enregistré.", Toast.LENGTH_SHORT).show();
            } catch (SecurityException e) {
                showError("Android n’a pas accordé l’accès durable à ce dossier. Choisissez un dossier du téléphone "
                        + "(par exemple dans Documents).");
            }
            refreshLibrary();
        }
    }

    // ================================================================================================
    // Affichage

    private void showHome() {
        result = null;
        home.setVisibility(View.VISIBLE);
        working.setVisibility(View.GONE);
        done.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        refreshLibrary();
    }

    // ================================================================================================
    // Dossier Chat2Doc

    /** Ouvre le document d'une discussion enregistrée ; s'il y en a un par année, demande lequel. */
    private void openSaved(Library.Entry e, List<Uri> docs) {
        if (docs.size() == 1) {
            if (docs.get(0) != null) openDocument(docs.get(0));
            else Toast.makeText(this, "Document Word introuvable dans le dossier.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[docs.size()];
        for (int k = 0; k < docs.size(); k++) {
            String n = e.docxNames.get(docs.size() - 1 - k);
            labels[k] = n.replaceAll("(?i)\\.docx$", "");
        }
        new AlertDialog.Builder(this)
                .setTitle(e.title)
                .setItems(labels, (d, which) -> {
                    Uri u = docs.get(docs.size() - 1 - which);
                    if (u != null) openDocument(u);
                    else Toast.makeText(this, "Document Word introuvable dans le dossier.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_FOLDER);
        } catch (ActivityNotFoundException e) {
            showError("Aucun sélecteur de dossiers n’est disponible sur ce téléphone.");
        }
    }

    private void forgetFolder() {
        new AlertDialog.Builder(this)
                .setTitle("Revenir au mode .zip ?")
                .setMessage("Chat2Doc n’utilisera plus le dossier choisi : chaque conversion produira une archive .zip "
                        + "indépendante. Le dossier et son contenu restent intacts sur le téléphone.")
                .setPositiveButton("Revenir au mode .zip", (d, w) -> {
                    Library.forget(this);
                    refreshLibrary();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Lit (hors du fil de l'interface) l'état du dossier Chat2Doc et la liste des discussions enregistrées. */
    private void refreshLibrary() {
        if (busy) return;
        final boolean chosen = Library.treeUri(this) != null;
        worker.execute(() -> {
            Library lib = Library.open(this);
            String name = lib == null ? null : lib.name();
            List<Library.Entry> entries = lib == null ? new ArrayList<>() : lib.entries();
            List<List<Uri>> docs = new ArrayList<>();
            for (Library.Entry e : entries) docs.add(lib.documentUris(e));
            ui.post(() -> showLibrary(chosen, name, entries, docs));
        });
    }

    private void showLibrary(boolean chosen, String name, List<Library.Entry> entries, List<List<Uri>> docs) {
        if (isFinishing() || isDestroyed()) return;
        if (name != null) {
            folderStatus.setText("Dossier « " + name + " » : chaque discussion y est conservée et complétée à chaque "
                    + "nouvel export, au-delà des limites de WhatsApp.");
            folderChoose.setText("Changer de dossier…");
        } else if (chosen) {
            folderStatus.setText("Le dossier choisi n’est plus accessible (supprimé ou déplacé). Choisissez-le à nouveau.");
            folderChoose.setText("Choisir le dossier…");
        } else {
            folderStatus.setText("Aucun dossier : chaque conversion produit une archive .zip indépendante. Avec un dossier, "
                    + "les discussions sont conservées sur le téléphone et complétées à chaque nouvel export.");
            folderChoose.setText("Choisir le dossier…");
        }
        folderForget.setVisibility(chosen ? View.VISIBLE : View.GONE);

        savedList.removeAllViews();
        savedTitle.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        float dp = getResources().getDisplayMetrics().density;
        for (int k = 0; k < entries.size(); k++) {
            Library.Entry e = entries.get(k);
            List<Uri> doc = docs.get(k);
            TextView tv = new TextView(this);
            StringBuilder t = new StringBuilder(e.title);
            if (e.first != null && e.last != null) {
                t.append("\n").append(DocxWriter.dateFr(e.first.toLocalDate(), false)).append(" → ")
                        .append(DocxWriter.dateFr(e.last.toLocalDate(), false));
            }
            t.append("\n").append(count(e.messages, "message", "messages"));
            if (e.docxNames.size() > 1) t.append(" · ").append(e.docxNames.size()).append(" documents (un par année)");
            if (e.updated != null) {
                t.append(" · mis à jour le ").append(DocxWriter.dateFr(e.updated.toLocalDate(), false))
                        .append(" à ").append(HOUR.format(e.updated));
            }
            android.text.SpannableString span = new android.text.SpannableString(t);
            span.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, e.title.length(), 0);
            span.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.cg_blue)), 0, e.title.length(), 0);
            tv.setText(span);
            tv.setTextSize(14);
            tv.setTextColor(getColor(R.color.text_main));
            tv.setBackgroundResource(R.drawable.card);
            int pad = (int) (14 * dp);
            tv.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (8 * dp);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> openSaved(e, doc));
            savedList.addView(tv);
        }
    }

    private void showWorking() {
        home.setVisibility(View.GONE);
        done.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        working.setVisibility(View.VISIBLE);
        stage.setText("Préparation…");
        cancelButton.setText(R.string.cancel);
        progressDetail.setText("");
        progress.setIndeterminate(true);
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private void showLicence() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.bal_title)
                .setMessage(R.string.bal_text)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton("Code source", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB)));
                    } catch (ActivityNotFoundException ignored) {
                        // pas de navigateur
                    }
                })
                .show();
    }

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private int qualityIndex() {
        int id = quality.getCheckedRadioButtonId();
        return id == R.id.quality_small ? 0 : id == R.id.quality_original ? 2 : 1;
    }

    /** Relais de l'avancement vers l'écran, limité à une mise à jour par image affichée. */
    private final class UiProgress implements ProgressListener {
        private final AtomicBoolean pending = new AtomicBoolean();
        private volatile String lastStage = "";
        private volatile int lastDone, lastTotal;

        @Override
        public void onProgress(String s, int d, int t) {
            lastStage = s;
            lastDone = d;
            lastTotal = t;
            if (pending.compareAndSet(false, true)) {
                ui.postDelayed(() -> {
                    pending.set(false);
                    if (!busy || cancelled) return;
                    stage.setText(lastStage + "…");
                    if (lastTotal > 0) {
                        progress.setIndeterminate(false);
                        progress.setMax(lastTotal);
                        progress.setProgress(Math.min(lastDone, lastTotal));
                        progressDetail.setText(String.format(Locale.FRENCH, "%,d / %,d", lastDone, lastTotal));
                    } else {
                        progress.setIndeterminate(true);
                        progressDetail.setText(lastDone > 0 ? String.format(Locale.FRENCH, "%,d", lastDone) : "");
                    }
                }, 120);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    // ================================================================================================

    private static String count(int n, String one, String many) {
        return String.format(Locale.FRENCH, "%,d", n) + " " + (n > 1 ? many : one);
    }

    private static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " Ko";
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.FRENCH, "%.1f Mo", bytes / 1048576.0);
        return String.format(Locale.FRENCH, "%.2f Go", bytes / 1073741824.0);
    }
}
