/*
 * Chat2Doc — CGExcel
 * Convertit une discussion WhatsApp exportée en document Word, avec les médias rangés à côté.
 * (c) 2026 Cyrille Gindre — Licence MIT + BAL 1.0 (Bonne Action License)
 * En échange, une seule chose vous est demandée, sur l'honneur : faire une bonne action chaque jour.
 * Aider un voisin, sourire à un inconnu, ramasser un papier… c'est vous qui voyez.
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
import android.widget.EditText;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
    private static final int REQ_FOLDER = 3;
    private static final String PREFS = "chat2doc";
    private static final String PREF_QUALITY = "qualite_photos";
    private static final String PREF_SPLIT = "decoupage";
    private static final String PREF_SPLIT_MB = "decoupage_mo";
    private static final int DEFAULT_SPLIT_MB = 20;
    private static final int[] QUALITY_PX = {800, 1280, 0};
    private static final String GITHUB = "https://github.com/Cyrille31/chat2doc";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH);
    private static final String BAL = "Chat2Doc est un logiciel libre, publié sous licence MIT + BAL 1.0 (Bonne Action License). "
            + "En échange, une seule chose vous est demandée, sur l’honneur : faire une bonne action chaque jour. "
            + "Aider un voisin, sourire à un inconnu, ramasser un papier… c’est vous qui voyez.";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private View home, settings, working, done, folderNeeded, discussion;
    private TextView stage, progressDetail, summary, error, folderStatus, savedEmpty;
    private ProgressBar progress;
    private RadioGroup quality, split;
    private EditText splitMax;
    private Button cancelButton, folderChoose;
    private LinearLayout savedList;

    private volatile boolean cancelled;
    /** Récupération des aperçus en cours, et demande de l'utilisateur d'ignorer les aperçus restants. */
    private volatile boolean fetchingPreviews, skipPreviews;
    private static boolean previewsRequested;
    /** Nom du dossier de sauvegarde mis à jour par la dernière conversion. */
    private static volatile String libraryName;
    /** Éléments du partage qui n'ont pas pu être lus (pour le message d'erreur), ou {@code null}. */
    private volatile String receiveReport;
    private boolean busy;
    private Converter.Result result;
    /** Adresses, dans le dossier de sauvegarde, des documents Word de la dernière conversion. */
    private volatile List<Uri> resultUris = new ArrayList<>();
    /** Conversion demandée avant le choix du dossier : reprise dès qu'il est choisi. */
    private List<Uri> pendingUris;
    private String pendingText, pendingSubject;

    // ================================================================================================
    // Cycle de vie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        home = findViewById(R.id.home);
        settings = findViewById(R.id.settings);
        discussion = findViewById(R.id.discussion);
        findViewById(R.id.discussion_back).setOnClickListener(v -> showHome());
        working = findViewById(R.id.working);
        done = findViewById(R.id.done);
        folderNeeded = findViewById(R.id.folder_needed);
        stage = findViewById(R.id.stage);
        progressDetail = findViewById(R.id.progress_detail);
        summary = findViewById(R.id.summary);
        error = findViewById(R.id.error);
        progress = findViewById(R.id.progress);
        folderStatus = findViewById(R.id.folder_status);
        savedEmpty = findViewById(R.id.saved_empty);
        savedList = findViewById(R.id.saved_list);
        quality = findViewById(R.id.quality);
        split = findViewById(R.id.split);
        splitMax = findViewById(R.id.split_max);
        folderChoose = findViewById(R.id.folder_choose);
        cancelButton = findViewById(R.id.cancel);

        // Réglages
        int q = prefs.getInt(PREF_QUALITY, 1);
        quality.check(q == 0 ? R.id.quality_small : q == 2 ? R.id.quality_original : R.id.quality_standard);
        quality.setOnCheckedChangeListener((group, id) -> prefs.edit().putInt(PREF_QUALITY,
                id == R.id.quality_small ? 0 : id == R.id.quality_original ? 2 : 1).apply());
        int sp = prefs.getInt(PREF_SPLIT, Converter.SPLIT_AUTO);
        split.check(sp == Converter.SPLIT_NONE ? R.id.split_none : sp == Converter.SPLIT_YEAR ? R.id.split_year : R.id.split_auto);
        split.setOnCheckedChangeListener((group, id) -> {
            prefs.edit().putInt(PREF_SPLIT, id == R.id.split_none ? Converter.SPLIT_NONE
                    : id == R.id.split_year ? Converter.SPLIT_YEAR : Converter.SPLIT_AUTO).apply();
            splitMax.setEnabled(id == R.id.split_auto);
        });
        splitMax.setText(String.valueOf(prefs.getInt(PREF_SPLIT_MB, DEFAULT_SPLIT_MB)));
        splitMax.setEnabled(sp == Converter.SPLIT_AUTO);
        ((TextView) findViewById(R.id.about)).setText("Chat2Doc " + versionName() + "\n© 2026 Cyrille Gindre — CGExcel\n\n"
                + BAL + "\n\nTout le traitement se fait sur votre téléphone. Internet ne sert qu’à récupérer les aperçus "
                + "des liens, et seulement si vous l’acceptez : aucun contenu de vos discussions n’est envoyé.\n\n"
                + "Chat2Doc n’est ni affilié à WhatsApp ni approuvé par WhatsApp ou Meta.");

        // Boutons
        findViewById(R.id.open_settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.close_settings).setOnClickListener(v -> showHome());
        findViewById(R.id.pick_zip).setOnClickListener(v -> pickZip());
        findViewById(R.id.folder_needed_choose).setOnClickListener(v -> chooseFolder());
        folderChoose.setOnClickListener(v -> chooseFolder());
        findViewById(R.id.source_code).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB)));
            } catch (ActivityNotFoundException ignored) {
                // pas de navigateur
            }
        });
        cancelButton.setOnClickListener(v -> {
            if (fetchingPreviews) {
                skipPreviews = true;
                stage.setText("Aperçus restants ignorés, suite de la conversion…");
            } else {
                cancelled = true;
                stage.setText("Annulation…");
            }
        });
        findViewById(R.id.share).setOnClickListener(v -> share());
        findViewById(R.id.open_word).setOnClickListener(v -> openWord());
        findViewById(R.id.again).setOnClickListener(v -> showHome());

        if (savedInstanceState == null) handleIntent(getIntent());
        else showHome();
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
    protected void onPause() {
        saveSplitMax();
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (busy) {
            Toast.makeText(this, "Conversion en cours : touchez « Annuler » pour l’interrompre.", Toast.LENGTH_SHORT).show();
        } else if (settings.getVisibility() == View.VISIBLE || done.getVisibility() == View.VISIBLE
                || discussion.getVisibility() == View.VISIBLE) {
            showHome();
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

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void saveSplitMax() {
        int mb = DEFAULT_SPLIT_MB;
        try {
            mb = Math.max(1, Integer.parseInt(splitMax.getText().toString().trim()));
        } catch (RuntimeException ignored) {
            // saisie vide ou invalide : valeur par défaut
        }
        prefs.edit().putInt(PREF_SPLIT_MB, mb).apply();
    }

    // ================================================================================================
    // Réception du partage

    private void handleIntent(Intent intent) {
        if (intent == null) {
            showHome();
            return;
        }
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
        if (Library.treeUri(this) == null) {
            // Pas encore de dossier de sauvegarde : on le demande, puis la conversion reprend
            pendingUris = uris;
            pendingText = sharedText;
            pendingSubject = subject;
            showHome();
            new AlertDialog.Builder(this)
                    .setTitle("Où ranger vos discussions ?")
                    .setMessage(getString(R.string.folder_needed))
                    .setPositiveButton("Choisir le dossier…", (d, w) -> chooseFolder())
                    .setNegativeButton("Annuler", (d, w) -> pendingUris = null)
                    .show();
            return;
        }
        saveSplitMax();
        busy = true;
        cancelled = false;
        result = null;
        showWorking();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final Converter.Options opt = new Converter.Options();
        opt.imageMaxPx = QUALITY_PX[prefs.getInt(PREF_QUALITY, 1)];
        opt.split = prefs.getInt(PREF_SPLIT, Converter.SPLIT_AUTO);
        opt.maxBytes = prefs.getInt(PREF_SPLIT_MB, DEFAULT_SPLIT_MB) * 1024L * 1024L;
        opt.makeZip = false;
        opt.dayFirstByDefault = !Locale.getDefault().getCountry().equals("US");
        final ProgressListener listener = new UiProgress();

        worker.execute(() -> {
            try {
                receiveReport = null;
                File jobs = new File(getCacheDir(), "jobs");
                Zips.deleteRecursively(jobs);
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
                opt.titleHint = hint;
                Converter.Inspection ins = Converter.inspect(in, opt);

                Library lib = Library.open(this);
                if (lib == null) {
                    throw new IOException("Le dossier de sauvegarde n’est plus accessible (supprimé ou déplacé). "
                            + "Choisissez-le à nouveau dans Réglages.");
                }
                Archive archive = lib.checkout(ins.safe, new File(work, ins.safe), listener);
                prepareThen(ins, archive, lib, in, tmp, opt, listener);
            } catch (ProgressListener.CancelledException e) {
                ui.post(() -> finished(null, "Conversion annulée."));
            } catch (Throwable t) {
                ui.post(() -> finished(null, describe(t)));
            }
        });
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
                    List<Uri> uris = new ArrayList<>();
                    for (String v : r.volumes) uris.add(lib.fileUri(r.folder.getName(), v));
                    resultUris = uris;
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
        int n = 0, ok = 0;
        StringBuilder report = new StringBuilder();
        for (Uri uri : uris) {
            if (listener.isCancelled()) throw new ProgressListener.CancelledException();
            listener.onProgress("Réception des fichiers", n++, uris.size());
            String name = displayName(uri);
            String type = getContentResolver().getType(uri);
            File tmp = unique(dir, name + ".part");
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IOException("flux vide");
                try (OutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
                }
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                // Un élément illisible (dossier, lien expiré...) ne doit pas bloquer les autres
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                report.append("\n• ").append(name).append(" (").append(type).append(") : illisible");
                continue;
            }
            ok++;
            // On reconnaît le contenu lui-même : certaines versions de WhatsApp envoient des noms sans extension
            byte[] head = new byte[4096];
            int len;
            try (InputStream in = new FileInputStream(tmp)) {
                len = Math.max(0, in.read(head));
            }
            boolean zip = len >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
            if (zip) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(tmp), 1 << 16)) {
                    Zips.unzip(in, dir, listener);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                zipName = name;
            } else {
                String finalName = name;
                if (!name.toLowerCase(Locale.ROOT).endsWith(".txt") && name.indexOf('.') < 0
                        && ChatParser.looksLikeChat(new String(head, 0, len, StandardCharsets.UTF_8))) {
                    finalName = name + ".txt";
                }
                if (!tmp.renameTo(unique(dir, finalName))) throw new IOException("Espace de travail indisponible.");
            }
        }
        listener.onProgress("Réception des fichiers", uris.size(), uris.size());
        receiveReport = report.length() == 0 ? null
                : "Éléments reçus de WhatsApp : " + uris.size() + ", lisibles : " + ok + "." + report;
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
        hideAll();
        done.setVisibility(View.VISIBLE);
    }

    private static String describe(Converter.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("« ").append(r.title).append(" »");
        if (libraryName != null) {
            sb.append("\nEnregistrée dans : ").append(libraryName).append(" › ").append(r.folder.getName());
        }
        sb.append("\n\n").append(count(r.stats.messages, "message", "messages")).append(" de ")
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

        sb.append("\n\n").append(r.volumes.size() > 1 ? "Documents Word : " : "Document Word : ")
                .append(String.join(", ", r.volumes)).append(".");
        if (r.update && r.volumes.size() > 1 && !r.rewritten.isEmpty() && r.rewritten.size() < r.volumes.size()) {
            sb.append("\nMis à jour : ").append(String.join(", ", r.rewritten)).append(".");
        }
        sb.append("\n").append(count(r.embeddedPictures, "photo insérée", "photos insérées")).append(", ")
                .append(count(r.mediaFiles, "média rangé", "médias rangés")).append(" dans les sous-dossiers.");
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
        if (r.warning != null) sb.append("\n\n⚠ ").append(r.warning);
        return sb.toString();
    }

    private String describe(Throwable t) {
        String m = String.valueOf(t.getMessage());
        if (t instanceof OutOfMemoryError) {
            return "Mémoire insuffisante. Choisissez « Photos compactes » et recommencez.";
        }
        if (m.contains("ENOSPC") || m.toLowerCase(Locale.ROOT).contains("no space")) {
            return "Espace de stockage insuffisant sur le téléphone. Libérez de la place (il faut environ "
                    + "deux fois la taille de l’export) puis recommencez.";
        }
        String msg = "La conversion a échoué.\n\n" + (t.getMessage() != null ? t.getMessage() : t.toString());
        if (receiveReport != null) {
            msg += "\n\n" + receiveReport + "\n\nSolution de secours : dans WhatsApp, exportez la discussion vers "
                    + "« Mes fichiers » ou Google Drive (enregistrement), puis dans Chat2Doc touchez "
                    + "« Convertir un export WhatsApp enregistré ».";
        }
        return msg;
    }

    // ================================================================================================
    // Partage, ouverture

    private void share() {
        if (result == null) return;
        pickResultDocument("Partager quel document ?", this::shareDocument);
    }

    private void shareDocument(Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(DOCX);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_SUBJECT, result.title + " — discussion WhatsApp");
        i.setClipData(ClipData.newRawUri("document", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, getString(R.string.share)));
    }

    private void openWord() {
        if (result == null) return;
        pickResultDocument("Ouvrir quel document ?", this::openDocument);
    }

    /** Un seul document : action directe ; plusieurs (un par période) : on demande lequel, du plus ancien au plus récent. */
    private void pickResultDocument(String title, java.util.function.Consumer<Uri> action) {
        List<String> names = result.volumes;
        List<Uri> uris = new ArrayList<>();
        for (int k = 0; k < names.size(); k++) {
            Uri u = k < resultUris.size() ? resultUris.get(k) : null;
            File local = new File(result.folder, names.get(k));
            if (u == null && local.exists()) u = FileProvider.getUriForFile(this, getPackageName() + ".fichiers", local);
            uris.add(u);
        }
        chooseDocument(title, names, uris, action);
    }

    private void chooseDocument(String title, List<String> names, List<Uri> uris, java.util.function.Consumer<Uri> action) {
        if (names.size() == 1) {
            if (uris.get(0) != null) action.accept(uris.get(0));
            else Toast.makeText(this, "Document Word introuvable dans le dossier.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[names.size()];
        for (int k = 0; k < names.size(); k++) labels[k] = names.get(k).replaceAll("(?i)\\.docx$", "");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (d, which) -> {
                    Uri u = uris.get(which);
                    if (u != null) action.accept(u);
                    else Toast.makeText(this, "Document Word introuvable dans le dossier.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
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
        if (requestCode == REQ_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    Library.choose(this, data.getData());
                    Toast.makeText(this, "Dossier de sauvegarde enregistré.", Toast.LENGTH_SHORT).show();
                } catch (SecurityException e) {
                    showError("Android n’a pas accordé l’accès durable à ce dossier. Choisissez un dossier du téléphone "
                            + "(par exemple dans Documents).");
                }
            }
            if (pendingUris != null && Library.treeUri(this) != null) {
                List<Uri> u = pendingUris;
                pendingUris = null;
                convert(u, pendingText, pendingSubject);
            } else {
                pendingUris = null;
                if (settings.getVisibility() == View.VISIBLE) showSettings(); else showHome();
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_PICK_ZIP) convert(Collections.singletonList(data.getData()), null, null);
    }

    // ================================================================================================
    // Affichage

    private void hideAll() {
        home.setVisibility(View.GONE);
        settings.setVisibility(View.GONE);
        discussion.setVisibility(View.GONE);
        working.setVisibility(View.GONE);
        done.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
    }

    private void showHome() {
        result = null;
        hideAll();
        home.setVisibility(View.VISIBLE);
        refreshLibrary();
    }

    private void showSettings() {
        if (busy) return;
        hideAll();
        settings.setVisibility(View.VISIBLE);
        refreshLibrary();
    }

    private void showWorking() {
        hideAll();
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

    // ================================================================================================
    // Dossier de sauvegarde

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
            folderStatus.setText("Dossier « " + name + " » : chaque discussion y a son propre dossier (document Word, "
                    + "photos, vidéos…), complété à chaque nouvel export.");
            folderChoose.setText("Changer de dossier…");
        } else if (chosen) {
            folderStatus.setText("Le dossier choisi n’est plus accessible (supprimé ou déplacé). Choisissez-le à nouveau.");
            folderChoose.setText(R.string.folder_choose);
        } else {
            folderStatus.setText("Aucun dossier choisi.");
            folderChoose.setText(R.string.folder_choose);
        }
        folderNeeded.setVisibility(name == null ? View.VISIBLE : View.GONE);
        if (chosen && name == null) {
            ((TextView) findViewById(R.id.folder_needed_text)).setText("Le dossier de sauvegarde n’est plus accessible "
                    + "(supprimé ou déplacé). Choisissez-le à nouveau.");
        }

        savedList.removeAllViews();
        savedEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
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
            if (e.docxNames.size() > 1) t.append(" · ").append(e.docxNames.size()).append(" documents Word");
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

    /** Ouvre le document d'une discussion enregistrée ; s'il y en a un par année, demande lequel. */
    /** Fiche d'une discussion enregistrée : ses documents Word et ses pièces jointes, à ouvrir d'un toucher. */
    private void openSaved(Library.Entry e, List<Uri> docs) {
        hideAll();
        discussion.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.discussion_title)).setText(e.title);
        StringBuilder info = new StringBuilder();
        if (e.first != null && e.last != null) {
            info.append(DocxWriter.dateFr(e.first.toLocalDate(), false)).append(" → ")
                    .append(DocxWriter.dateFr(e.last.toLocalDate(), false)).append("\n");
        }
        info.append(count(e.messages, "message", "messages"));
        if (e.updated != null) {
            info.append(" · mis à jour le ").append(DocxWriter.dateFr(e.updated.toLocalDate(), false))
                    .append(" à ").append(HOUR.format(e.updated));
        }
        ((TextView) findViewById(R.id.discussion_info)).setText(info);

        LinearLayout words = findViewById(R.id.discussion_words);
        words.removeAllViews();
        for (int k = 0; k < e.docxNames.size(); k++) {
            Uri u = docs.get(k);
            words.addView(item("📄  " + e.docxNames.get(k).replaceAll("(?i)\\.docx$", ""), v -> {
                if (u != null) openDocument(u);
                else Toast.makeText(this, "Document Word introuvable dans le dossier.", Toast.LENGTH_SHORT).show();
            }));
        }

        LinearLayout att = findViewById(R.id.discussion_attachments);
        att.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Lecture du dossier…");
        loading.setTextColor(getColor(R.color.text_soft));
        att.addView(loading);
        final String[][] subs = {{"Documents", "Documents"}, {"Videos", "Vidéos"}, {"Audio", "Messages vocaux et audio"},
                {"Contacts", "Contacts"}, {"Autres", "Autres fichiers"}};
        worker.execute(() -> {
            Library lib = Library.open(this);
            List<List<Library.Node>> lists = new ArrayList<>();
            for (String[] sub : subs) lists.add(lib == null ? new ArrayList<>() : lib.files(e.folder, sub[0]));
            ui.post(() -> {
                if (discussion.getVisibility() != View.VISIBLE) return;
                att.removeAllViews();
                boolean any = false;
                for (int k = 0; k < subs.length; k++) {
                    List<Library.Node> files = lists.get(k);
                    if (files.isEmpty()) continue;
                    any = true;
                    TextView h = new TextView(this);
                    h.setText(subs[k][1] + " (" + files.size() + ")");
                    h.setTextColor(getColor(R.color.cg_blue));
                    h.setTextSize(14);
                    h.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
                    att.addView(h);
                    for (Library.Node f : files) att.addView(item(f.name, v -> openAttachment(f.uri, f.name)));
                }
                if (!any) {
                    TextView none = new TextView(this);
                    none.setText("Aucune pièce jointe (hors photos, qui sont dans le document Word).");
                    none.setTextColor(getColor(R.color.text_soft));
                    att.addView(none);
                }
            });
        });
    }

    /** Ligne cliquable d'une liste. */
    private TextView item(String text, View.OnClickListener click) {
        float dp = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(getColor(R.color.text_main));
        tv.setBackgroundResource(R.drawable.card);
        int pad = (int) (12 * dp);
        tv.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (6 * dp);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(click);
        return tv;
    }

    /** Ouvre une pièce jointe avec l'application adaptée (lecteur PDF, vidéo, audio...). */
    private void openAttachment(Uri uri, String name) {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (ext.equals("opus")) mime = "audio/ogg";
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime != null ? mime : "*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "Aucune application du téléphone ne sait ouvrir ce fichier.", Toast.LENGTH_LONG).show();
        }
    }

    // ================================================================================================

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

    private static String count(int n, String one, String many) {
        return String.format(Locale.FRENCH, "%,d", n) + " " + (n > 1 ? many : one);
    }
}
