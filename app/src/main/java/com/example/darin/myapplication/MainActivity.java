package com.example.darin.myapplication;

import android.content.Context;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.StringBuilder;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String kOverviewUrl = "overview://";

    private final WebViewCache mWebViewCache = new WebViewCache();
    private static final History mHistory = new History();

    private static String getRegistryControlledDomain(String url) {
        try {
            URL parsedUrl = new URL(url);

            // This is the cheesy method that only works for foo.{com,org,net} etc.
            String host = parsedUrl.getHost();
            int lastDot = host.lastIndexOf('.');
            if (lastDot != -1) {
                int nextToLastDot = host.lastIndexOf('.', lastDot - 1);
                int startOfDomain = nextToLastDot + 1;
                return host.substring(startOfDomain);
            }
            return host;  // XXX Not sure what else to do here.
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static String fixupUrl(String url) {
        try {
            URL parsedUrl = new URL(url);
            return parsedUrl.toString();
        } catch (MalformedURLException e) {
            int offset = url.indexOf(':');
            if (offset == -1) {
                // Try adding http://
                return fixupUrl("http://" + url);
            }
            return url;  // Not sure what else to do here.
        }
    }

    private static String fixupInput(String text) {
        // Might be a search or URL
        if (text.indexOf('.') != -1 || text.indexOf(':') != -1)
            return fixupUrl(text);
        // Try searching then.
        return "https://www.google.com/#q=" + text;
    }

    @Override
    protected void onCreate(Bundle state) {
        Log.v(TAG, "CREATE (state is " + (state == null ? "null)" : "not null)"));

        super.onCreate(state);
        setContentView(R.layout.activity_main);

        EditText urlView = (EditText) findViewById(R.id.urlView);
        urlView.setImeActionLabel("Go", KeyEvent.KEYCODE_ENTER);

        urlView.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                // If the event is a key-down event on the "enter" button
                if ((event.getAction() == KeyEvent.ACTION_DOWN) &&
                        (keyCode == KeyEvent.KEYCODE_ENTER)) {
                    TextView urlView = (TextView) v;
                    navigate(fixupInput(urlView.getText().toString()));
                    return true;
                }
                return false;
            }
        });

        Button allButton = (Button) findViewById(R.id.allButton);
        allButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                navigateToOverview();
            }
        });

        mHistory.load(this);
    }

    /*
    @Override
    protected void onRestoreInstanceState(Bundle state) {
        Log.v(TAG, "RESTORE");
        ArrayList<History.Record> records = state.getParcelableArrayList("history.0");
        Log.v(TAG, " ... records length: " + new Integer(records.size()).toString());
        mHistory.putRecords(records);
        super.onRestoreInstanceState(state);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        Log.v(TAG, "SAVE");
        super.onSaveInstanceState(state);
        state.putParcelableArrayList("history.0", mHistory.getRecords());
    }
    */

    @Override
    protected void onPause() {
        Log.v(TAG, "PAUSE");
        super.onPause();

        mHistory.save(this);
    }

    /*
    @Override
    protected void onResume() {
        Log.v(TAG, "RESUME");
        super.onResume();
    }
    */

    private void hideOldWebView() {
        ViewGroup contentAreaView = (ViewGroup) findViewById(R.id.contentAreaView);
        WebView oldWebView = (WebView) contentAreaView.getChildAt(0);
        if (oldWebView != null) {
            contentAreaView.removeViewAt(0);
            mWebViewCache.put(oldWebView);
        }
    }

    private WebView createWebView() {
        WebView webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webView.setWebViewClient(new MyWebViewClient(this));
        return webView;
    }

    private void showWebView(WebView webView) {
        ViewGroup contentAreaView = (ViewGroup) findViewById(R.id.contentAreaView);
        contentAreaView.addView(webView);
    }

    private void navigate(String url) {
        hideOldWebView();

        WebView newWebView = mWebViewCache.get(url);
        if (newWebView == null)
            newWebView = createWebView();
        showWebView(newWebView);

        newWebView.loadUrl(url);
    }

    private void navigateBackTo(String domain) {
        hideOldWebView();

        String url = fixupUrl(domain);

        WebView existingWebView = mWebViewCache.get(url);
        if (existingWebView == null) {
            Log.v(TAG, "navigateBackTo CACHE MISS for domain: " + domain);
            navigate(url);
            // TODO: Need some way to restore scroll position.
        } else {
            Log.v(TAG, "navigateBackTo CACHE HIT for domain: " + domain);
            mHistory.recordNavigation(url);
            showWebView(existingWebView);
        }
    }

    private void navigateToOverview() {
        hideOldWebView();

        WebView newWebView = createWebView();
        showWebView(newWebView);

        newWebView.loadDataWithBaseURL(
                kOverviewUrl, generateOverviewHTML(), "text/html", null, kOverviewUrl);
    }

    private String generateOverviewHTML() {
        StringBuilder result = new StringBuilder();

        Date now = new Date();

        Iterator<History.Record> iter = mHistory.getSortedUrls().iterator();
        while (iter.hasNext()) {
            History.Record record = iter.next();
            long deltaSec = (now.getTime() - record.mLastVisited.getTime()) / 1000;
            result.append("<p>");
            result.append("<a href='goto:");
            result.append(record.mLastUrl);
            result.append("'>");
            result.append(getRegistryControlledDomain(record.mLastUrl));
            result.append(" (");
            result.append(new Long(record.mVisitCount).toString());
            result.append(" visits, ");
            result.append(new Long(deltaSec).toString());
            result.append(" seconds ago)");
            result.append("</a>");
        }

        return result.toString();
    }

    private class MyWebViewClient extends WebViewClient {
        private MyWebViewClient(MainActivity activity) {
            this.mActivity = activity;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            Log.v(TAG, "page commit, url: " + url);

            mHistory.recordNavigation(url);

            TextView urlView = (TextView) mActivity.findViewById(R.id.urlView);
            if (url.equals(kOverviewUrl)) {
                urlView.setText("");
            } else {
                urlView.setText(url);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Log.v(TAG, "current url: " + view.getUrl());
            Log.v(TAG, "new url: " + url);

            if (url.startsWith("goto:")) {
                navigateBackTo(url.substring(5));
                return true;
            }

            if (getRegistryControlledDomain(view.getUrl()) == getRegistryControlledDomain(url))
                return false;

            navigate(url);
            return true;
        }

        private MainActivity mActivity;
    }

    private static class WebViewCache {
        // TODO: add some recycling
        private LruCache<String, WebView> mCache = new LruCache<String, WebView>(10);

        public WebView get(String url) {
            String domain = getRegistryControlledDomain(url);
            if (domain == null || domain.isEmpty())
                return null;
            return mCache.get(domain);
        }

        public void put(WebView webView) {
            String domain = getRegistryControlledDomain(webView.getUrl());
            if (domain == null || domain.isEmpty())
                return;
            mCache.put(domain, webView);
        }
    }

    private static class History {
        public static class Record {
            public Date mLastVisited;
            public long mVisitCount;
            public String mLastUrl;

            public Record() {
                mVisitCount = 0;
            }
        }

        private HashMap<String, Record> mDB = new HashMap<String, Record>();

        public void load(Context context) {
            String jsonText;
            try {
                StringBuffer buffer = new StringBuffer();

                FileInputStream inputStream = context.openFileInput("history.json");

                int count, offset = 0;
                while ((count = inputStream.available()) > 0) {
                    byte[] bytes = new byte[count];
                    inputStream.read(bytes, offset, count);
                    offset += count;

                    buffer.append(new String(bytes));
                }

                jsonText = buffer.toString();
            } catch (Exception ex) {
                ex.printStackTrace();
                return;
            }

            try {
                JSONObject jsonRoot = new JSONObject(jsonText);

                mDB.clear();

                JSONArray jsonRecords = jsonRoot.getJSONArray("records");
                for (int i = 0; i < jsonRecords.length(); ++i) {
                    JSONObject jsonRecord = (JSONObject) jsonRecords.get(i);

                    Record record = new Record();
                    record.mLastVisited = new Date(jsonRecord.getLong("lastvisited"));
                    record.mVisitCount = jsonRecord.getLong("visitcount");
                    record.mLastUrl = jsonRecord.getString("lasturl");

                    mDB.put(getRegistryControlledDomain(record.mLastUrl), record);
                }
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }

        public void save(Context context) {
            JSONObject jsonRoot = new JSONObject();
            try {
                JSONArray jsonRecords = new JSONArray();
                int index = 0;

                Iterator<Map.Entry<String, Record>> iter = mDB.entrySet().iterator();
                while (iter.hasNext()) {
                    Record record = iter.next().getValue();

                    JSONObject jsonRecord = new JSONObject();
                    jsonRecord.put("lastvisited", record.mLastVisited.getTime());
                    jsonRecord.put("visitcount", record.mVisitCount);
                    jsonRecord.put("lasturl", record.mLastUrl);

                    jsonRecords.put(index++, jsonRecord);
                }

                jsonRoot.put("records", jsonRecords);
            } catch (JSONException ex) {
                ex.printStackTrace();
            }

            try {
                FileOutputStream outputStream =
                        context.openFileOutput("history.json", context.MODE_PRIVATE);
                outputStream.write(jsonRoot.toString().getBytes());
                outputStream.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        public void recordNavigation(String url) {
            Log.v(TAG, "record navigation to: " + url);
            String domain = getRegistryControlledDomain(url);
            if (domain == null)
                return;

            Record record = mDB.get(domain);
            if (record == null) {
                record = new Record();
                mDB.put(domain, record);
            }
            record.mLastVisited = new Date();
            record.mVisitCount++;
            record.mLastUrl = url;
            Log.v(TAG, " ... last visited: " + record.mLastVisited.toString());
        }

        /*
        public ArrayList<Record> getRecords() {
            ArrayList<Record> results = new ArrayList<Record>();

            Iterator<Map.Entry<String, Record>> iter = mDB.entrySet().iterator();
            while (iter.hasNext())
                results.add(iter.next().getValue());

            return results;
        }

        public void putRecords(ArrayList<Record> records) {
            mDB.clear();

            Iterator<Record> iter = records.iterator();
            while (iter.hasNext()) {
                Record record = iter.next();
                mDB.put(getRegistryControlledDomain(record.mLastUrl), record);
            }
        }
        */

        public ArrayList<Record> getSortedUrls() {
            // TODO: There is surely a more efficient solution to use here.

            ArrayList<Record> results = new ArrayList<Record>();

            ArrayList<Map.Entry<String, Record>> data = new ArrayList<Map.Entry<String, Record>>();

            Iterator<Map.Entry<String, Record>> iter = mDB.entrySet().iterator();
            while (iter.hasNext())
                data.add(iter.next());

            final Date now = new Date();

            Collections.sort(data, new Comparator<Map.Entry<String, Record>>() {
                public int compare(Map.Entry<String, Record> a, Map.Entry<String, Record> b) {
                    Record a_rec = a.getValue();
                    Record b_rec = b.getValue();

                    double a_delta = (double) now.getTime() - a_rec.mLastVisited.getTime();
                    double b_delta = (double) now.getTime() - b_rec.mLastVisited.getTime();

                    a_delta /= a_rec.mVisitCount;
                    b_delta /= b_rec.mVisitCount;

                    if (a_delta < b_delta)
                        return -1;
                    if (a_delta > b_delta)
                        return +1;
                    return 0;
                }
            });

            Iterator<Map.Entry<String, Record>> dataIter = data.iterator();
            while (dataIter.hasNext())
                results.add(dataIter.next().getValue());

            return results;
        }
    }
}
