package net.jejer.hipda.async;

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.text.TextUtils;

import net.jejer.hipda.R;
import net.jejer.hipda.bean.SimpleListBean;
import net.jejer.hipda.bean.SimpleListItemBean;
import net.jejer.hipda.db.History;
import net.jejer.hipda.db.HistoryDao;
import net.jejer.hipda.job.SimpleListEvent;
import net.jejer.hipda.okhttp.NetworkError;
import net.jejer.hipda.okhttp.OkHttpHelper;
import net.jejer.hipda.utils.Constants;
import net.jejer.hipda.utils.HiParser;
import net.jejer.hipda.utils.HiUtils;
import net.jejer.hipda.utils.Logger;

import org.greenrobot.eventbus.EventBus;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

public class SimpleListLoader extends AsyncTaskLoader<SimpleListBean> {
    public static final int TYPE_MYREPLY = 0;
    public static final int TYPE_MYPOST = 1;
    public static final int TYPE_SEARCH = 2;
    public static final int TYPE_SMS = 3;
    public static final int TYPE_THREAD_NOTIFY = 4;
    public static final int TYPE_SMS_DETAIL = 5;
    public static final int TYPE_FAVORITES = 6;
    public static final int TYPE_SEARCH_USER_THREADS = 7;
    public static final int TYPE_ATTENTION = 8;
    public static final int TYPE_HISTORIES = 9;

    private Context mCtx;
    private int mType;
    private int mPage = 1;
    private String mExtra = "";
    private SimpleListBean data;

    public SimpleListLoader(Context context, int type, int page, String extra) {
        super(context);
        mCtx = context;
        mType = type;
        mPage = page;
        mExtra = extra;
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();
        if (data != null) {
            deliverResult(data);
        }
        if (data == null || takeContentChanged()) {
            forceLoad();
        }
    }

    @Override
    public SimpleListBean loadInBackground() {
        if (mType == TYPE_HISTORIES) {
            data = new SimpleListBean();
            List<History> histories = HistoryDao.getHistories();
            for (History history : histories) {
                SimpleListItemBean bean = new SimpleListItemBean();
                String forumName = "";
                if (!TextUtils.isEmpty(history.getFid()) && TextUtils.isDigitsOnly(history.getFid()))
                    forumName = HiUtils.getForumNameByFid(Integer.parseInt(history.getFid()));
                bean.setTid(history.getTid());
                bean.setUid(history.getUid());
                bean.setTitle(history.getTitle());
                bean.setTime(forumName + " " + history.getPostTime());
                bean.setAvatarUrl(HiUtils.getAvatarUrlByUid(history.getUid()));
                bean.setForum(history.getUsername());
                data.add(bean);
            }
            return data;
        }

        for (int i = 0; i < OkHttpHelper.MAX_RETRY_TIMES; i++) {
            try {
                String resp = fetchSimpleList(mType);
                if (resp != null) {
                    if (!LoginHelper.checkLoggedin(mCtx, resp)) {
                        int status = new LoginHelper(mCtx, null).login();
                        if (status == Constants.STATUS_FAIL_ABORT) {
                            break;
                        }
                    } else {
                        Document doc = Jsoup.parse(resp);
                        data = HiParser.parseSimpleList(mCtx, mType, doc);
                        return data;
                    }
                }
            } catch (Exception e) {
                SimpleListEvent event = new SimpleListEvent();
                NetworkError message = OkHttpHelper.getErrorMessage(e);
                event.mMessage = message.getMessage();
                event.mDetail = message.getDetail();
                EventBus.getDefault().post(event);
            }
        }
        return null;
    }

    private String fetchSimpleList(int type) throws Exception {
        String url = null;
        switch (type) {
            case TYPE_MYREPLY:
                url = HiUtils.MyReplyUrl + "&page=" + mPage;
                break;
            case TYPE_MYPOST:
                url = HiUtils.MyPostUrl + "&page=" + mPage;
                break;
            case TYPE_SMS:
                url = HiUtils.SMSUrl;
                break;
            case TYPE_THREAD_NOTIFY:
                url = HiUtils.ThreadNotifyUrl;
                break;
            case TYPE_SMS_DETAIL:
                url = HiUtils.SMSDetailUrl + mExtra;
                break;
            case TYPE_SEARCH:
                try {
                    String prefixsft = mCtx.getResources().getString(R.string.prefix_search_fulltext);
                    if (mExtra.startsWith(prefixsft)) {
                        url = HiUtils.SearchFullText + URLEncoder.encode(mExtra.substring(prefixsft.length()), "GBK");
                        if (mPage > 1)
                            url += "&page=" + mPage;
                    } else {
                        url = HiUtils.SearchTitle + URLEncoder.encode(mExtra, "GBK");
                        if (mPage > 1)
                            url += "&page=" + mPage;
                    }
                } catch (UnsupportedEncodingException e) {
                    Logger.e("Encoding error", e);
                }
                break;
            case TYPE_SEARCH_USER_THREADS:
                if (TextUtils.isDigitsOnly(mExtra)) {
                    //first search, use uid
                    url = HiUtils.SearchUserThreads + mExtra + "&page=" + mPage;
                } else {
                    //after first seach, searchId is generated
                    url = HiUtils.BaseUrl + mExtra;
                    //replace page number in url
                    int pageIndex = url.indexOf("page=");
                    int pageEndIndex = url.indexOf("&", pageIndex + "page=".length());
                    if (pageIndex > 0 && pageEndIndex > pageIndex) {
                        url = url.substring(0, pageIndex) + "page=" + mPage + url.substring(pageEndIndex);
                    } else if (pageEndIndex == -1) {
                        url = url.substring(0, pageIndex) + "page=" + mPage;
                    }
                }
                break;
            case TYPE_FAVORITES:
                url = HiUtils.FavoritesUrl;
                url = url.replace("{item}", FavoriteHelper.TYPE_FAVORITE);
                if (mPage > 1)
                    url += "&page=" + mPage;
                break;
            case TYPE_ATTENTION:
                url = HiUtils.FavoritesUrl;
                url = url.replace("{item}", FavoriteHelper.TYPE_ATTENTION);
                if (mPage > 1)
                    url += "&page=" + mPage;
                break;
            default:
                break;
        }
        return OkHttpHelper.getInstance().get(url);
    }
}
