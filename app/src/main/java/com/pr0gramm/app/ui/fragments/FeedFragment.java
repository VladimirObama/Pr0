package com.pr0gramm.app.ui.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.transition.TransitionInflater;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.pr0gramm.app.ActivityComponent;
import com.pr0gramm.app.R;
import com.pr0gramm.app.Settings;
import com.pr0gramm.app.UnlockService;
import com.pr0gramm.app.api.meta.ItemsInfo;
import com.pr0gramm.app.api.meta.MetaService;
import com.pr0gramm.app.api.meta.SizeInfo;
import com.pr0gramm.app.api.pr0gramm.response.Info;
import com.pr0gramm.app.api.pr0gramm.response.Message;
import com.pr0gramm.app.feed.ContentType;
import com.pr0gramm.app.feed.Feed;
import com.pr0gramm.app.feed.FeedFilter;
import com.pr0gramm.app.feed.FeedItem;
import com.pr0gramm.app.feed.FeedLoader;
import com.pr0gramm.app.feed.FeedService;
import com.pr0gramm.app.feed.FeedType;
import com.pr0gramm.app.services.BookmarkService;
import com.pr0gramm.app.services.EnhancedUserInfo;
import com.pr0gramm.app.services.Graph;
import com.pr0gramm.app.services.ImmutableEnhancedUserInfo;
import com.pr0gramm.app.services.LocalCacheService;
import com.pr0gramm.app.services.SeenService;
import com.pr0gramm.app.services.SingleShotService;
import com.pr0gramm.app.services.Track;
import com.pr0gramm.app.services.UriHelper;
import com.pr0gramm.app.services.UserService;
import com.pr0gramm.app.services.preloading.PreloadManager;
import com.pr0gramm.app.services.preloading.PreloadService;
import com.pr0gramm.app.ui.ContentTypeDrawable;
import com.pr0gramm.app.ui.DialogBuilder;
import com.pr0gramm.app.ui.FeedFilterFormatter;
import com.pr0gramm.app.ui.MainActionHandler;
import com.pr0gramm.app.ui.MainActivity;
import com.pr0gramm.app.ui.MergeRecyclerAdapter;
import com.pr0gramm.app.ui.MessageAdapter;
import com.pr0gramm.app.ui.OnOptionsItemSelected;
import com.pr0gramm.app.ui.OptionMenuHelper;
import com.pr0gramm.app.ui.SingleViewAdapter;
import com.pr0gramm.app.ui.WriteMessageActivity;
import com.pr0gramm.app.ui.base.BaseFragment;
import com.pr0gramm.app.ui.dialogs.ErrorDialogFragment;
import com.pr0gramm.app.ui.views.BusyIndicator;
import com.pr0gramm.app.ui.views.CustomSwipeRefreshLayout;
import com.pr0gramm.app.ui.views.UserInfoCell;
import com.pr0gramm.app.ui.views.UserInfoFoundView;
import com.pr0gramm.app.util.AndroidUtility;
import com.squareup.picasso.Picasso;
import com.trello.rxlifecycle.FragmentEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import butterknife.Bind;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Actions;

import static com.google.common.base.Objects.equal;
import static com.google.common.base.Optional.absent;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.pr0gramm.app.ui.FeedFilterFormatter.feedTypeToString;
import static com.pr0gramm.app.ui.ScrollHideToolbarListener.ToolbarActivity;
import static com.pr0gramm.app.ui.ScrollHideToolbarListener.estimateRecyclerViewScrollY;
import static com.pr0gramm.app.util.AndroidUtility.checkMainThread;
import static com.pr0gramm.app.util.AndroidUtility.getStatusBarHeight;
import static com.pr0gramm.app.util.AndroidUtility.ifPresent;
import static java.lang.Math.max;
import static java.util.Collections.emptyList;

/**
 */
public class FeedFragment extends BaseFragment {
    private static final Logger logger = LoggerFactory.getLogger(FeedFragment.class);

    private static final String ARG_FEED_FILTER = "FeedFragment.filter";
    private static final String ARG_FEED_START = "FeedFragment.start.id";

    @Inject
    FeedService feedService;

    @Inject
    Picasso picasso;

    @Inject
    SeenService seenService;

    @Inject
    Settings settings;

    @Inject
    BookmarkService bookmarkService;

    @Inject
    UserService userService;

    @Inject
    MetaService metaService;

    @Inject
    SingleShotService singleShotService;

    @Inject
    LocalCacheService localCacheService;

    @Inject
    PreloadManager preloadManager;

    @Inject
    UnlockService unlockService;

    @Bind(R.id.list)
    RecyclerView recyclerView;

    @Bind(R.id.progress)
    BusyIndicator busyIndicator;

    @Bind(R.id.refresh)
    CustomSwipeRefreshLayout swipeRefreshLayout;

    @Bind(R.id.empty)
    View noResultsView;

    private boolean userInfoCommentsOpen;
    private boolean bookmarkable;
    private IndicatorStyle seenIndicatorStyle;
    private Long autoScrollOnLoad = null;
    private ItemWithComment autoOpenOnLoad = null;

    private FeedAdapter feedAdapter;
    private FeedLoader loader;

    /**
     * Initialize a new feed fragment.
     */
    public FeedFragment() {
        setHasOptionsMenu(true);
        setRetainInstance(true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // initialize auto opening
        ItemWithComment start = getArguments().getParcelable(ARG_FEED_START);
        if (start != null) {
            autoScrollOnLoad = start.getItemId();
            autoOpenOnLoad = start;
        }
    }

    @Override
    protected void injectComponent(ActivityComponent activityComponent) {
        activityComponent.inject(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        return inflater.inflate(R.layout.fragment_feed, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (feedAdapter == null) {
            if (busyIndicator != null)
                busyIndicator.setVisibility(View.VISIBLE);

            // create a new adapter if necessary
            feedAdapter = newFeedAdapter();

        } else {
            updateNoResultsTextView();
            removeBusyIndicator();
        }

        seenIndicatorStyle = settings.seenIndicatorStyle();

        // prepare the list of items
        int columnCount = getThumbnailColumns();
        GridLayoutManager layoutManager = new GridLayoutManager(getActivity(), columnCount);
        layoutManager.setSpanSizeLookup(new NMatchParentSpanSizeLookup(1, columnCount));
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemAnimator(null);
        setFeedAdapter(feedAdapter);

        // we can still swipe up if we are not at the start of the feed.
        swipeRefreshLayout.setCanSwipeUpPredicate(() -> !feedAdapter.getFeed().isAtStart());

        swipeRefreshLayout.setOnRefreshListener(() -> {
            Feed feed = feedAdapter.getFeed();
            if (feed.isAtStart() && !loader.isLoading()) {
                loader.restart(Optional.<Long>absent());
            } else {
                // do not refresh
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        // use height of the toolbar to configure swipe refresh layout.
        int abHeight = AndroidUtility.getActionBarContentOffset(getActivity());
        int offset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ?
                getStatusBarHeight(getActivity()) : 0;
        swipeRefreshLayout.setProgressViewOffset(false, offset, (int) (offset + 1.5 * (abHeight - offset)));
        swipeRefreshLayout.setColorSchemeResources(R.color.primary);

        resetToolbar();

        recyclerView.addOnScrollListener(onScrollListener);
    }

    private void setFeedAdapter(FeedAdapter adapter) {
        feedAdapter = adapter;

        MergeRecyclerAdapter merged = new MergeRecyclerAdapter();
        merged.addAdapter(SingleViewAdapter.of(FeedFragment::newFeedStartPaddingView));
        merged.addAdapter(adapter);
        recyclerView.setAdapter(merged);

        queryUserInfo()
                .first()
                .compose(bindToLifecycle())
                .subscribe(this::presentUserInfo, Actions.empty());
    }

    private void presentUserInfo(EnhancedUserInfo value) {
        if (getCurrentFilter().getTags().isPresent()) {
            presentUserUploadsHint(value.getInfo());
        } else {
            presentUserInfoCell(value);
        }
    }

    public void presentUserInfoCell(EnhancedUserInfo info) {
        MessageAdapter messages = new MessageAdapter(getActivity(), emptyList(), null, R.layout.user_info_comment) {
            @Override
            public void onBindViewHolder(MessageViewHolder view, int position) {
                super.onBindViewHolder(view, position);
                view.itemView.setOnClickListener(v -> {
                    Message message = this.messages.get(position);
                    Uri uri = UriHelper.of(getActivity()).post(FeedType.NEW,
                            message.getItemId(), message.getId());

                    startActivity(new Intent(Intent.ACTION_VIEW, uri,
                            getActivity(), MainActivity.class));
                });
            }
        };

        List<Message> comments = FluentIterable.from(info.getInfo().getComments())
                .transform(c -> Message.of(info.getInfo().getUser(), c))
                .toList();

        if (userInfoCommentsOpen) {
            messages.setMessages(comments);
        }

        UserInfoCell view = new UserInfoCell(getActivity(), info.getInfo());
        if (info.getBenisGraph().isPresent())
            view.setBenisGraph(info.getBenisGraph().get());

        view.setUserActionListener(new UserInfoCell.UserActionListener() {
            @Override
            public void onWriteMessageClicked(int userId, String name) {
                startActivity(WriteMessageActivity.intent(getActivity(), userId, name));
            }

            @Override
            public void onUserFavoritesClicked(String name) {
                FeedFilter filter = getCurrentFilter().basic().withLikes(name);
                if (!filter.equals(getCurrentFilter())) {
                    ((MainActionHandler) getActivity()).onFeedFilterSelected(filter);
                }

                showUserInfoComments(emptyList());
            }

            @Override
            public void onShowUploadsClicked(int id, String name) {
                FeedFilter filter = getCurrentFilter().basic().withUser(name);
                if (!filter.equals(getCurrentFilter())) {
                    ((MainActionHandler) getActivity()).onFeedFilterSelected(filter);
                }

                showUserInfoComments(emptyList());
            }

            @Override
            public void onShowCommentsClicked() {
                showUserInfoComments(messages.getItemCount() == 0 ? comments : emptyList());
            }

            private void showUserInfoComments(List<Message> comments) {
                userInfoCommentsOpen = comments.size() > 0;
                messages.setMessages(comments);
                updateSpanSizeLookup();
            }
        });

        view.setWriteMessageEnabled(!isSelfInfo(info.getInfo()));
        view.setShowCommentsEnabled(!comments.isEmpty());

        appendUserInfoAdapters(
                SingleViewAdapter.ofView(view),
                messages,
                SingleViewAdapter.ofLayout(R.layout.user_info_footer));
    }

    public void presentUserUploadsHint(Info info) {
        if (isSelfInfo(info))
            return;

        UserInfoFoundView view = new UserInfoFoundView(getActivity(), info);
        view.setUploadsClickedListener((userId, name) -> {
            FeedFilter newFilter = getCurrentFilter().basic().withUser(name);
            ((MainActionHandler) getActivity()).onFeedFilterSelected(newFilter);
        });

        appendUserInfoAdapters(SingleViewAdapter.ofView(view));
    }

    private void appendUserInfoAdapters(RecyclerView.Adapter... adapters) {
        if (adapters.length == 0) {
            return;
        }

        ifPresent(getMainAdapter(), adapter -> {
            for (int idx = 0; idx < adapters.length; idx++) {
                adapter.addAdapter(1 + idx, adapters[idx]);
            }

            updateSpanSizeLookup();
        });
    }

    private Observable<EnhancedUserInfo> queryUserInfo() {
        String queryString = null;

        FeedFilter filter = getFilterArgument();
        if (filter.getUsername().isPresent()) {
            queryString = filter.getUsername().get();

        } else if (filter.getTags().isPresent()) {
            queryString = filter.getTags().get();

        } else if (filter.getLikes().isPresent()) {
            queryString = filter.getLikes().get();
        }

        if (queryString != null) {
            Optional<EnhancedUserInfo> cached = localCacheService.getUserInfo(queryString);
            if (cached.isPresent()) {
                return Observable.just(cached.get());
            }

            Observable<Info> first = userService
                    .info(queryString, getSelectedContentType())
                    .onErrorResumeNext(Observable.empty());

            Observable<Optional<Graph>> second = metaService
                    .getBenisHistory(queryString)
                    .firstOrDefault(null)
                    .map(Optional::fromNullable)
                    .onErrorResumeNext(Observable.just(Optional.absent()));

            return Observable.zip(first, second, ImmutableEnhancedUserInfo::of)
                    .cast(EnhancedUserInfo.class)
                    .doOnNext(localCacheService::cacheUserInfo);

        } else {
            return Observable.empty();
        }
    }

    private void updateSpanSizeLookup() {
        ifPresent(getMainAdapter(), getRecyclerViewLayoutManager(), (adapter, layout) -> {
            int itemCount = 0;
            ImmutableList<? extends RecyclerView.Adapter<?>> adapters = adapter.getAdapters();
            for (int idx = 0; idx < adapters.size(); idx++) {
                if (adapters.get(idx) instanceof FeedAdapter)
                    break;

                itemCount += adapters.get(idx).getItemCount();
            }

            // skip items!
            int columnCount = layout.getSpanCount();
            layout.setSpanSizeLookup(new NMatchParentSpanSizeLookup(itemCount, columnCount));
        });
    }

    private static View newFeedStartPaddingView(Context context) {
        int height = AndroidUtility.getActionBarContentOffset(context);

        View view = new View(context);
        view.setLayoutParams(new ViewGroup.LayoutParams(1, height));
        return view;
    }

    private Optional<MergeRecyclerAdapter> getMainAdapter() {
        if (recyclerView != null) {
            RecyclerView.Adapter adapter = recyclerView.getAdapter();
            return Optional.fromNullable((MergeRecyclerAdapter) adapter);
        }

        return Optional.absent();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recyclerView.removeOnScrollListener(onScrollListener);
        AndroidUtility.uninjectViews(this);
    }

    private void removeBusyIndicator() {
        if (busyIndicator != null) {
            ViewParent parent = busyIndicator.getParent();
            ((ViewGroup) parent).removeView(busyIndicator);

            busyIndicator = null;
        }
    }

    private void resetToolbar() {
        if (getActivity() instanceof ToolbarActivity) {
            ToolbarActivity activity = (ToolbarActivity) getActivity();
            activity.getScrollHideToolbarListener().reset();
        }
    }

    private void onBookmarkableStateChanged(boolean bookmarkable) {
        this.bookmarkable = bookmarkable;
        getActivity().supportInvalidateOptionsMenu();
    }

    private FeedAdapter newFeedAdapter() {
        logger.info("Restore adapter now");
        FeedFilter feedFilter = getFilterArgument();

        Optional<Long> around = Optional.fromNullable(autoOpenOnLoad).transform(ItemWithComment::getItemId);
        return newFeedAdapter(feedFilter, around);
    }

    private FeedAdapter newFeedAdapter(FeedFilter feedFilter, Optional<Long> around) {
        Feed feed = new Feed(feedFilter, getSelectedContentType());

        loader = new FeedLoader(new FeedLoader.Binder() {
            @Override
            public <T> Observable.Transformer<T, T> bind() {
                return observable -> observable
                        .compose(bindUntilEvent(FragmentEvent.DESTROY_VIEW))
                        .finallyDo(FeedFragment.this::onFeedLoadFinished);
            }

            @Override
            public void onError(Throwable error) {
                checkMainThread();
                onFeedError(error);
            }
        }, feedService, feed);

        // start loading now
        loader.restart(around);

        return new FeedAdapter(this, feed);
    }

    private FeedFilter getFilterArgument() {
        return getArguments().<FeedFilter>getParcelable(ARG_FEED_FILTER);
    }

    private EnumSet<ContentType> getSelectedContentType() {
        if (!userService.isAuthorized())
            return EnumSet.of(ContentType.SFW);

        return settings.getContentType();
    }

    @Override
    public void onResume() {
        super.onResume();

        // check if we should show the pin button or not.
        if (settings.showPinButton()) {
            bookmarkService.isBookmarkable(getCurrentFilter())
                    .compose(bindToLifecycle())
                    .subscribe(this::onBookmarkableStateChanged, Actions.empty());
        }

        recheckContentTypes();

        // set new indicator style
        if (seenIndicatorStyle != settings.seenIndicatorStyle()) {
            seenIndicatorStyle = settings.seenIndicatorStyle();
            feedAdapter.notifyDataSetChanged();
        }

        preloadManager.all()
                .compose(bindToLifecycle())
                .subscribe(ignored -> feedAdapter.notifyDataSetChanged());
    }

    private void recheckContentTypes() {
        // check if content type has changed, and reload if necessary
        FeedFilter feedFilter = feedAdapter.getFilter();
        EnumSet<ContentType> newContentType = getSelectedContentType();
        boolean changed = !equal(feedAdapter.getContentType(), newContentType);
        if (changed) {
            Optional<Long> around = autoOpenOnLoad != null
                    ? Optional.of(autoOpenOnLoad.getItemId())
                    : findLastVisibleFeedItem(newContentType).transform(FeedItem::getId);

            autoScrollOnLoad = around.orNull();

            // set a new adapter if we have a new content type
            setFeedAdapter(newFeedAdapter(feedFilter, around));

            getActivity().supportInvalidateOptionsMenu();
        }
    }

    /**
     * Finds the first item in the proxy, that is visible and of one of the given content type.
     *
     * @param contentType The target-content type.
     */
    private Optional<FeedItem> findLastVisibleFeedItem(Set<ContentType> contentType) {
        List<FeedItem> items = feedAdapter.getFeed().getItems();

        return getRecyclerViewLayoutManager().<Optional<FeedItem>>transform(layoutManager -> {
            MergeRecyclerAdapter adapter = (MergeRecyclerAdapter) recyclerView.getAdapter();
            int offset = adapter.getOffset(feedAdapter).or(0);

            // if the first row is visible, skip this stuff.
            if (layoutManager.findFirstCompletelyVisibleItemPosition() == 0)
                return absent();

            int idx = layoutManager.findLastCompletelyVisibleItemPosition() - offset;
            if (idx != RecyclerView.NO_POSITION && idx > 0 && idx < items.size()) {
                for (FeedItem item : Lists.reverse(items.subList(0, idx))) {
                    if (contentType.contains(item.getContentType())) {
                        return Optional.of(item);
                    }
                }
            }


            return absent();
        }).get();
    }

    /**
     * Depending on whether the screen is landscape or portrait, and how large
     * the screen is, we show a different number of items per row.
     */
    private int getThumbnailColumns() {
        checkNotNull(getActivity(), "must be attached to call this method");

        Configuration config = getResources().getConfiguration();
        boolean portrait = config.screenWidthDp < config.screenHeightDp;

        int screenWidth = config.screenWidthDp;
        return Math.min((int) (screenWidth / 120.0 + 0.5), portrait ? 5 : 7);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_feed, menu);

        MenuItem item = menu.findItem(R.id.action_search);
        if (item != null) {
            boolean searchable = getCurrentFilter().getFeedType().searchable();
            if (searchable) {
                initializeSearchView(item);
            } else {
                item.setVisible(false);
            }
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        if (getActivity() == null)
            return;

        FeedFilter filter = getCurrentFilter();
        FeedType feedType = filter.getFeedType();

        MenuItem item = menu.findItem(R.id.action_refresh);
        if (item != null) {
            item.setVisible(settings.showRefreshButton());
        }

        item = menu.findItem(R.id.action_pin);
        if (item != null) {
            item.setVisible(bookmarkable);
        }

        item = menu.findItem(R.id.action_preload);
        if (item != null) {
            item.setVisible(feedType.preloadable() && !AndroidUtility.isOnMobile(getActivity()));
        }

        item = menu.findItem(R.id.action_change_content_type);
        if (item != null) {
            if (userService.isAuthorized() && unlockService.unlocked()) {
                ContentTypeDrawable icon = new ContentTypeDrawable(getActivity(), getSelectedContentType());

                icon.setTextSize(getResources().getDimensionPixelSize(
                        R.dimen.feed_content_type_action_icon_text_size));

                item.setIcon(icon);
                item.setVisible(true);

                updateContentTypeItems(menu);

            } else {
                item.setVisible(false);
            }
        }
    }

    private void updateContentTypeItems(Menu menu) {
        boolean single = settings.getContentType().size() == 1;

        Map<Integer, Boolean> types = ImmutableMap.<Integer, Boolean>builder()
                .put(R.id.action_content_type_sfw, settings.getContentTypeSfw())
                .put(R.id.action_content_type_nsfw, settings.getContentTypeNsfw())
                .put(R.id.action_content_type_nsfl, settings.getContentTypeNsfl())
                .build();

        for (Map.Entry<Integer, Boolean> entry : types.entrySet()) {
            MenuItem item = menu.findItem(entry.getKey());
            if (item != null) {
                item.setChecked(entry.getValue());
                item.setEnabled(!single || !entry.getValue());
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Map<Integer, String> contentTypes = ImmutableMap.<Integer, String>builder()
                .put(R.id.action_content_type_sfw, "pref_feed_type_sfw")
                .put(R.id.action_content_type_nsfw, "pref_feed_type_nsfw")
                .put(R.id.action_content_type_nsfl, "pref_feed_type_nsfl")
                .build();

        if (contentTypes.containsKey(item.getItemId())) {
            boolean newState = !item.isChecked();
            settings.edit()
                    .putBoolean(contentTypes.get(item.getItemId()), newState)
                    .apply();

            // this applies the new content types and refreshes the menu.
            recheckContentTypes();
            return true;
        }

        return OptionMenuHelper.dispatch(this, item) || super.onOptionsItemSelected(item);
    }

    @OnOptionsItemSelected(R.id.action_refresh)
    public void refreshWithIndicator() {
        if (swipeRefreshLayout.isRefreshing())
            return;

        swipeRefreshLayout.setRefreshing(true);
        swipeRefreshLayout.postDelayed(() -> {
            resetToolbar();
            loader.restart(Optional.<Long>absent());
        }, 500);
    }

    @OnOptionsItemSelected(R.id.action_pin)
    public void pinCurrentFeedFilter() {
        // not bookmarkable anymore.
        onBookmarkableStateChanged(false);

        FeedFilter filter = getCurrentFilter();
        String title = FeedFilterFormatter.format(getActivity(), filter);
        ((MainActionHandler) getActivity()).pinFeedFilter(filter, title);
    }

    @OnOptionsItemSelected(R.id.action_preload)
    public void preloadCurrentFeed() {
        if (AndroidUtility.isOnMobile(getActivity())) {
            DialogBuilder.start(getActivity())
                    .content(R.string.preload_not_on_mobile)
                    .positive()
                    .show();

            return;
        }


        Intent intent = PreloadService.newIntent(getActivity(), feedAdapter.getFeed().getItems());
        getActivity().startService(intent);

        Track.preloadCurrentFeed();

        if (singleShotService.isFirstTime("preload_info_hint")) {
            DialogBuilder.start(getActivity())
                    .content(R.string.preload_info_hint)
                    .positive()
                    .show();
        }
    }

    /**
     * Registers the listeners for the search view.
     *
     * @param item The item containing the search view.
     */
    private void initializeSearchView(MenuItem item) {
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(item);

        searchView.setOnSearchClickListener(v -> {
            FeedFilter currentFilter = getCurrentFilter();
            searchView.setQuery(currentFilter.getTags().or(""), false);
        });

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String term) {
                performSearch(term);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String term) {
                return false;
            }
        });

        String typeName = feedTypeToString(getActivity(), getCurrentFilter().withTags("dummy"));
        searchView.setQueryHint(getString(R.string.action_search, typeName));
    }

    private void performSearch(String term) {
        FeedFilter current = getCurrentFilter();
        FeedFilter filter = current.withTags(term);

        // do nothing, if the filter did not change
        if (equal(current, filter))
            return;

        ((MainActionHandler) getActivity()).onFeedFilterSelected(filter);

        Track.search(term);
    }

    private void onItemClicked(int idx, Optional<Long> commentId, Optional<ImageView> preview) {
        Feed feed = feedAdapter.getFeed();
        if (idx < 0 || idx >= feed.size())
            return;

        try {
            boolean doTransition = false;
            PostPagerFragment fragment = PostPagerFragment.newInstance(feed, idx, commentId);

            if (preview.isPresent()) {
                // pass preview info to target fragment.
                Drawable image = preview.get().getDrawable();
                fragment.setPreviewInfo(buildPreviewInfo(feed.at(idx), image));

                // enable transition, if possible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    TransitionInflater inflater = TransitionInflater.from(getActivity());
                    fragment.setSharedElementEnterTransition(
                            inflater.inflateTransition(android.R.transition.move));

                    doTransition = true;
                }
            }

            @SuppressLint("CommitTransaction")
            FragmentTransaction tr = getFragmentManager().beginTransaction()
                    .replace(R.id.content, fragment)
                    .addToBackStack(null);

            if (doTransition) {
                tr.addSharedElement(preview.orNull(), "TransitionTarget-" + feed.at(idx).getId());
            }

            tr.commitAllowingStateLoss();

        } catch (IllegalStateException error) {
            logger.warn("Error while showing post", error);
        }
    }

    private PostFragment.PreviewInfo buildPreviewInfo(FeedItem item, Drawable image) {
        Optional<SizeInfo> sizeInfo = getSizeInfo(item);
        int sizeWidth = sizeInfo.transform(SizeInfo::getWidth).or(-1);
        int sizeHeight = sizeInfo.transform(SizeInfo::getHeight).or(-1);
        return new PostFragment.PreviewInfo(item.getId(), image, sizeWidth, sizeHeight);
    }

    /**
     * Creates a new {@link FeedFragment} for the given
     * feed type.
     *
     * @param feedFilter A query to use for getting data
     * @return The type new fragment that can be shown now.
     */
    public static FeedFragment newInstance(FeedFilter feedFilter, Optional<ItemWithComment> start) {
        Bundle arguments = new Bundle();
        arguments.putParcelable(ARG_FEED_FILTER, feedFilter);
        arguments.putParcelable(ARG_FEED_START, start.orNull());

        FeedFragment fragment = new FeedFragment();
        fragment.setArguments(arguments);
        return fragment;
    }

    /**
     * Gets the current filter from this feed.
     *
     * @return The filter this feed uses.
     */
    public FeedFilter getCurrentFilter() {
        if (feedAdapter == null)
            return new FeedFilter();

        return feedAdapter.getFilter();
    }

    private void onMetaServiceResponse(ItemsInfo itemsInfo) {
        if (!itemsInfo.getReposts().isEmpty()) {
            // we need to tell the recycler view to rebind the items, because
            // some items might now be reposts.
            feedAdapter.notifyDataSetChanged();
        }
    }

    private boolean isSeen(FeedItem item) {
        return seenService.isSeen(item);
    }

    public Optional<SizeInfo> getSizeInfo(FeedItem item) {
        return localCacheService.getSizeInfo(item.getId());
    }

    private static class FeedAdapter extends RecyclerView.Adapter<FeedItemViewHolder> implements Feed.FeedListener {
        private final WeakReference<FeedFragment> parent;
        private final Feed feed;

        public FeedAdapter(FeedFragment fragment, Feed feed) {
            this.parent = new WeakReference<>(fragment);
            this.feed = feed;
            this.feed.setFeedListener(this);

            setHasStableIds(true);
        }

        private void with(Action1<FeedFragment> action) {
            FeedFragment fragment = parent.get();
            if (fragment != null) {
                action.call(fragment);
            }
        }

        public FeedFilter getFilter() {
            return feed.getFeedFilter();
        }

        public Feed getFeed() {
            return feed;
        }

        @SuppressLint("InflateParams")
        @Override
        public FeedItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(this.parent.get().getActivity());
            View view = inflater.inflate(R.layout.feed_item_view, null);
            return new FeedItemViewHolder(view);
        }

        @Override
        public void onBindViewHolder(FeedItemViewHolder view, int position) {
            FeedItem item = feed.at(position);

            with(fragment -> {
                Uri imageUri = UriHelper.get().thumbnail(item);
                fragment.picasso.load(imageUri)
                        .placeholder(new ColorDrawable(0xff333333))
                        .into(view.image);

                view.itemView.setOnClickListener(v -> {
                    ViewCompat.setTransitionName(view.image, "TransitionTarget");
                    fragment.onItemClicked(position, Optional.<Long>absent(), Optional.of(view.image));
                });

                // show preload-badge
                view.setIsPreloaded(fragment.preloadManager.exists(item.getId()));

                // check if this item was already seen.
                if (fragment.localCacheService.isRepost(item.getId()) && fragment.settings.markRepostsInFeed()) {
                    view.setIsRepost();

                } else if (fragment.seenIndicatorStyle == IndicatorStyle.ICON && fragment.isSeen(item)) {
                    view.setIsSeen();

                } else {
                    view.clear();
                }
            });
        }

        @Override
        public int getItemCount() {
            return feed.size();
        }

        @Override
        public long getItemId(int position) {
            return feed.at(position).getId();
        }

        public Set<ContentType> getContentType() {
            return feed.getContentType();
        }

        @Override
        public void onNewItems(List<FeedItem> newItems) {
            // check if we prepended items to the list.
            int prependCount = 0;
            for (int idx = 0; idx < newItems.size(); idx++) {
                if (newItems.get(idx).getId() == getItemId(idx)) {
                    prependCount++;
                }
            }

            if (prependCount == 0) {
                notifyDataSetChanged();
            } else {
                notifyItemRangeInserted(0, prependCount);
            }

            // load meta data for the items.
            List<Long> itemIds = Lists.transform(newItems, FeedItem::getId);
            with(fragment -> fragment.loadMetaData(itemIds));
            with(FeedFragment::performAutoOpen);
        }

        @Override
        public void onRemoveItems() {
            notifyDataSetChanged();
        }
    }

    private void loadMetaData(List<Long> items) {
        Observable<ItemsInfo> metaData = metaService
                .getItemsInfo(items)
                .doOnNext(this::cacheInfoResponse);

        metaData.compose(bindToLifecycle())
                .onErrorResumeNext(Observable.<ItemsInfo>empty())
                .subscribe(this::onMetaServiceResponse, Actions.empty());
    }

    private void cacheInfoResponse(ItemsInfo itemsInfo) {
        logger.info("merge info about {} reposts and {} sizes",
                itemsInfo.getReposts().size(),
                itemsInfo.getSizes().size());

        for (SizeInfo sizeInfo : itemsInfo.getSizes())
            localCacheService.cacheSizeInfo(sizeInfo);

        // cache the items as reposts
        localCacheService.cacheReposts(itemsInfo.getReposts());
    }

    private void onFeedError(Throwable error) {
        logger.error("Error loading the feed", error);

        if (feedAdapter.getFeed().size() == 0) {
            if (autoOpenOnLoad != null) {
                ErrorDialogFragment.showErrorString(getFragmentManager(),
                        getString(R.string.could_not_load_feed_nsfw));
            } else {
                ErrorDialogFragment.showErrorString(getFragmentManager(),
                        getString(R.string.could_not_load_feed));
            }
        }
    }

    /**
     * Called when loading of feed data finished.
     */
    private void onFeedLoadFinished() {
        removeBusyIndicator();
        swipeRefreshLayout.setRefreshing(false);
        updateNoResultsTextView();
    }

    private void updateNoResultsTextView() {
        boolean empty = feedAdapter.getItemCount() == 0;
        noResultsView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    @SuppressWarnings("CodeBlock2Expr")
    private void performAutoOpen() {
        Feed feed = feedAdapter.getFeed();

        if (autoScrollOnLoad != null) {
            ifPresent(findItemIndexById(autoScrollOnLoad), idx -> {
                // over scroll a bit
                int scrollTo = max(idx + getThumbnailColumns(), 0);
                recyclerView.scrollToPosition(scrollTo);
            });
        }

        if (autoOpenOnLoad != null) {
            ifPresent(feed.indexOf(autoOpenOnLoad.getItemId()), idx -> {
                onItemClicked(idx, autoOpenOnLoad.getCommentId(), absent());
            });
        }

        autoOpenOnLoad = null;
        autoScrollOnLoad = null;
    }

    /**
     * Returns the item id of the index in the recycler views adapter.
     */
    private Optional<Integer> findItemIndexById(long id) {
        int offset = ((MergeRecyclerAdapter) recyclerView.getAdapter()).getOffset(feedAdapter).or(0);

        // look for the index of the item with the given id
        return FluentIterable
                .from(feedAdapter.getFeed().getItems())
                .firstMatch(item -> item.getId() == id)
                .transform(item -> feedAdapter.getFeed().indexOf(item).or(-1))
                .transform(idx -> idx + offset);
    }

    private Optional<GridLayoutManager> getRecyclerViewLayoutManager() {
        if (recyclerView == null)
            return absent();

        return Optional.fromNullable((GridLayoutManager) recyclerView.getLayoutManager());
    }

    private boolean isSelfInfo(Info info) {
        return userService.getName()
                .transform(info.getUser().getName()::equalsIgnoreCase)
                .or(false);
    }

    private final RecyclerView.OnScrollListener onScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (getActivity() instanceof ToolbarActivity) {
                ToolbarActivity activity = (ToolbarActivity) getActivity();
                activity.getScrollHideToolbarListener().onScrolled(dy);
            }

            ifPresent(getRecyclerViewLayoutManager(), layoutManager -> {
                if (loader.isLoading())
                    return;

                Feed feed = feedAdapter.getFeed();
                int totalItemCount = layoutManager.getItemCount();

                if (dy > 0 && !feed.isAtEnd()) {
                    int lastVisibleItem = layoutManager.findLastVisibleItemPosition();
                    if (totalItemCount > 12 && lastVisibleItem >= totalItemCount - 12) {
                        logger.info("Request next page now");
                        loader.next();
                    }
                }

                if (dy < 0 && !feed.isAtStart()) {
                    int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                    if (totalItemCount > 12 && firstVisibleItem < 12) {
                        logger.info("Request previous page now");
                        loader.previous();
                    }
                }
            });
        }

        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                if (getActivity() instanceof ToolbarActivity) {
                    int y = estimateRecyclerViewScrollY(recyclerView).or(Integer.MAX_VALUE);

                    ToolbarActivity activity = (ToolbarActivity) getActivity();
                    activity.getScrollHideToolbarListener().onScrollFinished(y);
                }
            }
        }
    };

    private static final class FeedItemViewHolder extends RecyclerView.ViewHolder {
        private final ImageView seen;
        private final ImageView repost;
        private final View preloaded;
        final ImageView image;

        public FeedItemViewHolder(View itemView) {
            super(itemView);

            image = (ImageView) checkNotNull(itemView.findViewById(R.id.image));
            seen = (ImageView) checkNotNull(itemView.findViewById(R.id.seen));
            repost = (ImageView) checkNotNull(itemView.findViewById(R.id.repost));
            preloaded = checkNotNull(itemView.findViewById(R.id.preloaded));
        }

        public void setIsRepost() {
            repost.setVisibility(View.VISIBLE);
            seen.setVisibility(View.GONE);
        }

        public void setIsSeen() {
            seen.setVisibility(View.VISIBLE);
            repost.setVisibility(View.GONE);
        }

        public void clear() {
            seen.setVisibility(View.GONE);
            repost.setVisibility(View.GONE);
        }

        public void setIsPreloaded(boolean isPreloaded) {
            preloaded.setVisibility(isPreloaded ? View.VISIBLE : View.GONE);
        }
    }
}