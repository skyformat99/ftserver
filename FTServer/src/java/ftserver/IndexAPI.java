package ftserver;

import ftserver.fts.Engine;
import ftserver.fts.KeyWord;
import iBoxDB.LocalServer.*;
import java.util.Date;
import java.util.*;
import java.util.concurrent.Callable;

public class IndexAPI {

    final static Engine ENGINE = new Engine();

    public static long Search(ArrayList<Page> outputPages,
            String name, long startId, long pageCount) {
        name = name.trim();
        try (Box box = App.cube()) {

            for (KeyWord kw : ENGINE.searchDistinct(box, name, startId, pageCount)) {

                startId = kw.I - 1;
                long id = kw.I;
                id = Page.rankDownId(id);
                Page p = box.d("Page", id).select(Page.class);
                p.keyWord = kw;
                outputPages.add(p);

            }

        }
        //Recommend
        if (outputPages.isEmpty() && name.length() > 1) {
            if (name.charAt(0) > 512) {
                //only search one char, if full search is empty
                Search(outputPages, name.substring(0, 1), Long.MAX_VALUE, pageCount);
            } else {
                int pos = name.indexOf(' ');
                if (pos > 0) {
                    name = name.substring(0, pos);
                    Search(outputPages, name, Long.MAX_VALUE, pageCount);
                }
            }
            if (outputPages.isEmpty()) {
                return startId;
            }
            return -1;
        }
        return startId;
    }

    public static String getDesc(String str, KeyWord kw, int length) {
        if (kw.I == -1) {
            return str;
        }
        return ENGINE.getDesc(str, kw, length);
    }

    public static ArrayList<String> discover() {
        ArrayList<String> discoveries = new ArrayList<>();

        try (Box box = App.Auto.cube()) {
            for (String skw : IndexAPI.ENGINE.discover(box, 'a', 'z', 2,
                    '\u2E80', '\u9fa5', 1)) {
                discoveries.add(skw);
            }
        }
        return discoveries;
    }

    public static String indexText(final String url, final boolean deleteOnly, final HashSet<String> subUrls) {
        boolean tran = true;
        if (tran) {
            return indexTextWithTran(Html.getUrl(url), deleteOnly, subUrls,
                    subUrls != null ? 1L << 59 : 0);
        }
        return indexTextNoTran(Html.getUrl(url), deleteOnly, subUrls);
    }

    private static String indexTextWithTran(final String url, final boolean deleteOnly, final HashSet<String> subUrls,
            final long rankUpPlus) {
        return pageLock(url, new Callable<String>() {

            @Override
            public String call() {
                try (Box box = App.Auto.cube()) {
                    Page defaultPage = null;

                    for (Page p : DB.toList(box.select(Page.class, "from Page where url==?", url))) {
                        ENGINE.indexText(box, p.id, p.content, true);
                        ENGINE.indexText(box, p.rankUpId(), p.rankUpDescription(), true);
                        box.d("Page", p.id).delete();
                        defaultPage = p;
                    }

                    if (deleteOnly) {
                        return box.commit() == CommitResult.OK ? "deleted" : "not deleted";
                    }

                    Page p = Html.get(url, subUrls);
                    if (p == null) {
                        //p = defaultPage;
                    }
                    if (p == null) {
                        return "temporarily unreachable";
                    } else {
                        if (p.id == 0) {
                            p.id = box.newId();
                            p.rankUpPlus = rankUpPlus;
                        }
                        box.d("Page").insert(p);
                        ENGINE.indexText(box, p.id, p.content, false);
                        ENGINE.indexText(box, p.rankUpId(), p.rankUpDescription(), false);
                        CommitResult cr = box.commit();
                        if (cr != CommitResult.OK) {
                            return cr.getErrorMsg(box);
                        }
                        return p.url;
                    }

                }
            }
        });
    }

    //No transaction, less memory
    private static String indexTextNoTran(final String url, final boolean deleteOnly, final HashSet<String> subUrls) {

        //url = Page.getUrl(url);
        final int BATCH_COMMIT = 200;
        return pageLock(url, new Callable<String>() {

            @Override
            public String call() {

                Page defaultPage = null;
                for (Page p : App.Auto.select(Page.class, "from Page where url==?", url)) {
                    ENGINE.indexTextNoTran(App.Auto, BATCH_COMMIT, p.id, p.content, true);
                    ENGINE.indexTextNoTran(App.Auto, BATCH_COMMIT, p.rankUpId(), p.rankUpDescription(), true);
                    App.Auto.delete("Page", p.id);
                    defaultPage = p;
                }

                if (deleteOnly) {
                    return "deleted";
                }

                Page p = Html.get(url, subUrls);
                if (p == null) {
                    p = defaultPage;
                }
                if (p == null) {
                    return "temporarily unreachable";
                } else {
                    if (p.id == 0) {
                        p.id = App.Auto.newId();
                        //   p.rankUpPlus = rankUpPlus;
                    }
                    App.Auto.insert("Page", p);
                    ENGINE.indexTextNoTran(App.Auto, BATCH_COMMIT, p.id, p.content, false);
                    ENGINE.indexTextNoTran(App.Auto, BATCH_COMMIT, p.rankUpId(), p.rankUpDescription(), false);
                    return p.url;
                }
            }
        });

    }

    private static String pageLock(final String url, final Callable<String> run) {
        try (Box box = App.Auto.cube()) {
            PageLock pl = box.d("PageLock", url).select(PageLock.class);
            if (pl == null) {
                pl = new PageLock();
                pl.url = url;
                pl.time = new Date();
            } else if ((new Date().getTime() - pl.time.getTime()) > 1000 * 60 * 5) {
                pl.time = new Date();
            } else {
                return "Running";
            }
            box.d("PageLock").replace(pl);
            if (box.commit() != CommitResult.OK) {
                return "Running";
            }
        }

        try {
            return run.call();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            App.Auto.delete("PageLock", url);
        }
    }
}
