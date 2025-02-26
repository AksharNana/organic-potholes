package app.organicmaps.widget.placepage;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollViewClickFixed;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.Framework;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.api.Const;
import app.organicmaps.api.ParsedMwmRequest;
import app.organicmaps.bookmarks.data.Bookmark;
import app.organicmaps.bookmarks.data.BookmarkCategory;
import app.organicmaps.bookmarks.data.BookmarkManager;
import app.organicmaps.bookmarks.data.Icon;
import app.organicmaps.bookmarks.data.MapObject;
import app.organicmaps.bookmarks.data.RoadWarningMarkType;
import app.organicmaps.routing.RoutingController;
import app.organicmaps.settings.RoadType;
import app.organicmaps.util.SharingUtils;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.bottomsheet.MenuBottomSheetFragment;
import app.organicmaps.util.bottomsheet.MenuBottomSheetItem;
import app.organicmaps.util.concurrency.ThreadPool;
import app.organicmaps.util.log.Logger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

public class PlacePageController extends Fragment implements
                                                  PlacePageView.PlacePageViewListener,
                                                  PlacePageButtons.PlacePageButtonClickListener,
                                                  MenuBottomSheetFragment.MenuBottomSheetInterface,
                                                  Observer<MapObject>
{
  private static final String TAG = PlacePageController.class.getSimpleName();
  private static final String PLACE_PAGE_BUTTONS_FRAGMENT_TAG = "PLACE_PAGE_BUTTONS";
  private static final String PLACE_PAGE_FRAGMENT_TAG = "PLACE_PAGE";

  private static final float PREVIEW_PLUS_RATIO = 0.45f;
  private BottomSheetBehavior<View> mPlacePageBehavior;
  private NestedScrollViewClickFixed mPlacePage;
  private ViewGroup mPlacePageContainer;
  private View mPlacePageStatusBarBackground;
  private ViewGroup mCoordinator;
  private int mViewportMinHeight;
  private int mButtonsHeight;
  private int mMaxButtons;
  private int mRoutingHeaderHeight;
  private PlacePageViewModel mViewModel;
  private int mPreviewHeight;
  private int mFrameHeight;
  @Nullable
  private MapObject mMapObject;
  @Nullable
  private MapObject mPreviousMapObject;
  private WindowInsetsCompat mCurrentWindowInsets;

  private boolean mShouldCollapse;
  private int mDistanceToTop;

  private ValueAnimator mCustomPeekHeightAnimator;
  private PlacePageRouteSettingsListener mPlacePageRouteSettingsListener;
  private final Observer<Integer> mPlacePageDistanceToTopObserver = this::updateStatusBarBackground;

  private final BottomSheetBehavior.BottomSheetCallback mDefaultBottomSheetCallback = new BottomSheetBehavior.BottomSheetCallback()
  {
    @Override
    public void onStateChanged(@NonNull View bottomSheet, int newState)
    {
      Logger.d(TAG, "State change, new = " + PlacePageUtils.toString(newState));
      if (PlacePageUtils.isSettlingState(newState) || PlacePageUtils.isDraggingState(newState))
        return;

      PlacePageUtils.updateMapViewport(mCoordinator, mDistanceToTop, mViewportMinHeight);

      if (PlacePageUtils.isHiddenState(newState))
        onHiddenInternal();
    }

    @Override
    public void onSlide(@NonNull View bottomSheet, float slideOffset)
    {
      stopCustomPeekHeightAnimation();
      mDistanceToTop = bottomSheet.getTop();
      mViewModel.setPlacePageDistanceToTop(mDistanceToTop);
    }
  };

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
  {
    return inflater.inflate(R.layout.place_page_container_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    final FragmentActivity activity = requireActivity();
    mPlacePageRouteSettingsListener = (MwmActivity) activity;

    final Resources res = activity.getResources();
    mViewportMinHeight = res.getDimensionPixelSize(R.dimen.viewport_min_height);
    mButtonsHeight = (int) res.getDimension(R.dimen.place_page_buttons_height);
    mMaxButtons = res.getInteger(R.integer.pp_buttons_max);
    mRoutingHeaderHeight = (int) res.getDimension(ThemeUtils.getResource(requireContext(), R.attr.actionBarSize));

    mCoordinator = activity.findViewById(R.id.coordinator);
    mPlacePage = view.findViewById(R.id.placepage);
    mPlacePageContainer = view.findViewById(R.id.placepage_container);
    mPlacePageBehavior = BottomSheetBehavior.from(mPlacePage);
    mPlacePageStatusBarBackground = view.findViewById(R.id.place_page_status_bar_background);

    mShouldCollapse = true;

    mPlacePageBehavior.setHideable(true);
    mPlacePageBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
    mPlacePageBehavior.setFitToContents(true);
    mPlacePageBehavior.setSkipCollapsed(true);

    UiUtils.bringViewToFrontOf(view.findViewById(R.id.pp_buttons_fragment), mPlacePage);

    mViewModel = new ViewModelProvider(requireActivity()).get(PlacePageViewModel.class);

    ViewCompat.setOnApplyWindowInsetsListener(mPlacePage, (v, windowInsets) -> {
      mCurrentWindowInsets = windowInsets;
      final Insets insets = mCurrentWindowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
      final ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) mPlacePageStatusBarBackground.getLayoutParams();
      // Layout calculations are heavy so we compute them once then move the view from behind the place page to the status bar
      layoutParams.height = insets.top;
      layoutParams.width = mPlacePage.getWidth();
      // Make sure the view is centered within the insets as is the place page
      layoutParams.setMargins(insets.left, 0, insets.right, 0);
      mPlacePageStatusBarBackground.setLayoutParams(layoutParams);
      return windowInsets;
    });
  }

  @NonNull
  private static PlacePageButtons.ButtonType toPlacePageButton(@NonNull RoadWarningMarkType type)
  {
    switch (type)
    {
      case DIRTY:
        return PlacePageButtons.ButtonType.ROUTE_AVOID_UNPAVED;
      case FERRY:
        return PlacePageButtons.ButtonType.ROUTE_AVOID_FERRY;
      case TOLL:
        return PlacePageButtons.ButtonType.ROUTE_AVOID_TOLL;
      default:
        throw new AssertionError("Unsupported road warning type: " + type);
    }
  }

  private void stopCustomPeekHeightAnimation()
  {
    if (mCustomPeekHeightAnimator != null && mCustomPeekHeightAnimator.isStarted())
    {
      mCustomPeekHeightAnimator.end();
      setPlacePageHeightBounds();
    }
  }

  private void onHiddenInternal()
  {
    Framework.nativeDeactivatePopup();
    PlacePageUtils.updateMapViewport(mCoordinator, mDistanceToTop, mViewportMinHeight);
    resetPlacePageHeightBounds();
    removePlacePageFragments();
  }

  @Nullable
  public ArrayList<MenuBottomSheetItem> getMenuBottomSheetItems(String id)
  {
    final List<PlacePageButtons.ButtonType> currentItems = mViewModel.getCurrentButtons().getValue();
    if (currentItems == null || currentItems.size() <= mMaxButtons)
      return null;
    ArrayList<MenuBottomSheetItem> items = new ArrayList<>();
    for (int i = mMaxButtons - 1; i < currentItems.size(); i++)
    {
      final PlacePageButton bsItem = PlacePageButtonFactory.createButton(currentItems.get(i), requireActivity());
      items.add(new MenuBottomSheetItem(
          bsItem.getTitle(),
          bsItem.getIcon(),
          () -> onPlacePageButtonClick(bsItem.getType())
      ));
    }
    return items;
  }

  private void setPlacePageInteractions(boolean enabled)
  {
    // Prevent place page scrolling when playing the close animation
    mPlacePageBehavior.setDraggable(enabled);
    mPlacePage.setNestedScrollingEnabled(enabled);
    // Prevent user interaction with place page content when closing
    mPlacePageContainer.setEnabled(enabled);
  }

  private void close()
  {
    setPlacePageInteractions(false);
    mPlacePageBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
  }

  private void resetPlacePageHeightBounds()
  {
    mFrameHeight = 0;
    mPlacePageContainer.setMinimumHeight(0);
    final int parentHeight = ((View) mPlacePage.getParent()).getHeight();
    mPlacePageBehavior.setMaxHeight(parentHeight);
  }

  /**
   * Set the min and max height of the place page to prevent jumps when switching from one map object
   * to the other.
   */
  private void setPlacePageHeightBounds()
  {
    final int peekHeight = calculatePeekHeight();
    final Insets insets = mCurrentWindowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
    // Make sure the place page can reach the peek height
    final int minHeight = Math.max(peekHeight, mFrameHeight);
    // Prevent the place page from showing under the status bar
    // If we are in planning mode, prevent going above the header
    final int topInsets = insets.top + (RoutingController.get().isPlanning() ? mRoutingHeaderHeight : 0);
    final int maxHeight = Math.min(minHeight + insets.bottom, mCoordinator.getHeight() - topInsets);
    // Set the minimum height of the place page to prevent jumps when new data results in SMALLER content
    // This cannot be set on the place page itself as it has the fitToContent property set
    mPlacePageContainer.setMinimumHeight(minHeight);
    // Set the maximum height of the place page to prevent jumps when new data results in BIGGER content
    // It does not take into account the navigation bar height so we need to add it manually
    mPlacePageBehavior.setMaxHeight(maxHeight);
  }

  /**
   * Make sure the place page can reach the peek height
   */
  private void preparePlacePageMinHeight(int peekHeight)
  {
    final int currentHeight = mPlacePageContainer.getHeight();
    if (currentHeight < peekHeight)
      mPlacePageContainer.setMinimumHeight(peekHeight);
  }

  private void setPeekHeight()
  {
    final int peekHeight = calculatePeekHeight();
    preparePlacePageMinHeight(peekHeight);

    final int state = mPlacePageBehavior.getState();
    // Do not animate the peek height if the place page should not be collapsed (eg: when returning from editor)
    final boolean shouldAnimate = !(PlacePageUtils.isExpandedState(state) && !mShouldCollapse) && !PlacePageUtils.isHiddenState(state);
    if (shouldAnimate)
      animatePeekHeight(peekHeight);
    else
    {
      mPlacePageBehavior.setPeekHeight(peekHeight);
      setPlacePageHeightBounds();
    }
  }

  /**
   * Using the animate param in setPeekHeight does not work when adding removing fragments
   * from inside the place page so we manually animate the peek height with ValueAnimator
   */
  private void animatePeekHeight(int peekHeight)
  {
    final int bottomInsets = mCurrentWindowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
    // Make sure to start from the current height of the place page
    final int parentHeight = ((View) mPlacePage.getParent()).getHeight();
    // Make sure to remove the navbar height because the peek height already takes it into account
    int initialHeight = parentHeight - mDistanceToTop - bottomInsets;

    if (mCustomPeekHeightAnimator != null)
      mCustomPeekHeightAnimator.cancel();
    mCustomPeekHeightAnimator = ValueAnimator.ofInt(initialHeight, peekHeight);
    mCustomPeekHeightAnimator.setInterpolator(new FastOutSlowInInterpolator());
    mCustomPeekHeightAnimator.addUpdateListener(valueAnimator -> {
      int value = (Integer) valueAnimator.getAnimatedValue();
      // Make sure the place page can reach the animated peek height to prevent jumps
      // maxHeight does not take the navbar height into account so we manually add it
      mPlacePageBehavior.setMaxHeight(value + bottomInsets);
      mPlacePageBehavior.setPeekHeight(value);
      // The place page is not firing the slide callbacks when using this animation, so we must call them manually
      mDistanceToTop = parentHeight - value - bottomInsets;
      mViewModel.setPlacePageDistanceToTop(mDistanceToTop);
      if (value == peekHeight)
      {
        PlacePageUtils.updateMapViewport(mCoordinator, mDistanceToTop, mViewportMinHeight);
        setPlacePageHeightBounds();
      }
    });
    mCustomPeekHeightAnimator.setDuration(200);
    mCustomPeekHeightAnimator.start();
  }

  private int calculatePeekHeight()
  {
    if (mMapObject != null && mMapObject.getOpeningMode() == MapObject.OPENING_MODE_PREVIEW_PLUS)
      return (int) (mCoordinator.getHeight() * PREVIEW_PLUS_RATIO);
    return mPreviewHeight + mButtonsHeight;
  }

  @Override
  public void onPlacePageContentChanged(int previewHeight, int frameHeight)
  {
    mPreviewHeight = previewHeight;
    mFrameHeight = frameHeight;
    mViewModel.setPlacePageWidth(mPlacePage.getWidth());
    mPlacePageStatusBarBackground.getLayoutParams().width = mPlacePage.getWidth();
    // Make sure to update the peek height on the UI thread to prevent weird animation jumps
    mPlacePage.post(() -> {
      setPeekHeight();
      if (mShouldCollapse && !PlacePageUtils.isCollapsedState(mPlacePageBehavior.getState()))
      {
        mPlacePageBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        // Make sure to reset the scroll position when opening the place page
        if (mPlacePage.getScrollY() != 0)
          mPlacePage.setScrollY(0);
      }
      mShouldCollapse = false;
    });
  }

  @Override
  public void onPlacePageRequestToggleState()
  {
    @BottomSheetBehavior.State
    int state = mPlacePageBehavior.getState();
    stopCustomPeekHeightAnimation();
    if (PlacePageUtils.isExpandedState(state))
      mPlacePageBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
    else
      mPlacePageBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
  }

  @Override
  public void onPlacePageRequestClose()
  {
    mPlacePageBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
  }

  @Override
  public void onPlacePageButtonClick(PlacePageButtons.ButtonType item)
  {
    switch (item)
    {
      case BOOKMARK_SAVE:
      case BOOKMARK_DELETE:
        onBookmarkBtnClicked();
        break;

      case BACK:
        onBackBtnClicked();
        break;

      case ROUTE_FROM:
        onRouteFromBtnClicked();
        break;

      case ROUTE_TO:
        onRouteToBtnClicked();
        break;

      case ROUTE_ADD:
        onRouteAddBtnClicked();
        break;

      case ROUTE_REMOVE:
        onRouteRemoveBtnClicked();
        break;

      case ROUTE_AVOID_TOLL:
        onAvoidTollBtnClicked();
        break;

      case ROUTE_AVOID_UNPAVED:
        onAvoidUnpavedBtnClicked();
        break;

      case ROUTE_AVOID_FERRY:
        onAvoidFerryBtnClicked();
        break;

      case POTHOLE:
        onBookmarkPotholeBtnClicked();
        break;
    }
  }

  /**
   * Calculates the absolute differences in latitude and longitude between two coordinates.
   *
   * @param lat1 The latitude of the first coordinate.
   * @param lon1 The longitude of the first coordinate.
   * @param lat2 The latitude of the second coordinate.
   * @param lon2 The longitude of the second coordinate.
   * @return An array containing the absolute differences in latitude and longitude.
   */
  private double[] calculateCoordinateDifference(double lat1, double lon1, double lat2, double lon2) {
    // Calculate the absolute differences in latitude and longitude
    double latDiff = Math.abs(lat1 - lat2);
    double lonDiff = Math.abs(lon1 - lon2);

    // Return an array containing the calculated differences
    return new double[]{latDiff, lonDiff};
  }

  /**
   * Checks if two coordinates are equal within a specified tolerance.
   *
   * @param lat1      The latitude of the first coordinate.
   * @param lon1      The longitude of the first coordinate.
   * @param lat2      The latitude of the second coordinate.
   * @param lon2      The longitude of the second coordinate.
   * @param tolerance The maximum allowed difference for coordinates to be considered equal.
   * @return True if the coordinates are equal within the specified tolerance, otherwise false.
   */
  private boolean areCoordinatesEqual(double lat1, double lon1, double lat2, double lon2, double tolerance) {
    // Calculate the absolute differences in latitude and longitude using the helper function
    double[] diffs = calculateCoordinateDifference(lat1, lon1, lat2, lon2);
    double latDiff = diffs[0];
    double lonDiff = diffs[1];

    // Check if both latitude and longitude differences are within the specified tolerance
    return latDiff < tolerance && lonDiff < tolerance;
  }

  /**
   * Handles the click event for the bookmark button.
   * If the mMapObject is not null, it either deletes a bookmark or adds a new one based on the mMapObject state.
   */
  private void onBookmarkBtnClicked()
  {
    // mMapObject is set to null when the place page closes
    // We don't want users to interact with the buttons when the PP is closing
    if (mMapObject == null)
      return;
    // No need to call setMapObject here as the native methods will reopen the place page
    if (mMapObject.isBookmark()){
      if(mMapObject.getName().equalsIgnoreCase("pothole")){
        System.out.println("Deleting pothole: " + mMapObject.getLat() + " " + mMapObject.getLon());
        OkHttpClient client = new OkHttpClient();

        // Get bookmark Categories
        List<BookmarkCategory> categories = BookmarkManager.INSTANCE.getCategories();

        // Find the category with the name "Potholes"
        long catId = 0;
        for(int i = 0; i < categories.size(); i++){
          if(categories.get(i).getName().equals("Potholes")){
            catId = categories.get(i).getId();
            break;
          }
        }
        long bookmarkID = -1;
        int bkmrkCount = BookmarkManager.INSTANCE.getCategoryById(catId).getBookmarksCount();
        System.out.println("mMapObject has coords: " + mMapObject.getLat() + "," + mMapObject.getLon());
        // Iterate through bookmarks in the category and find the matching bookmark
        for(int i = 0; i < bkmrkCount; i++){
          long id = BookmarkManager.INSTANCE.getBookmarkIdByPosition(catId,i);
          double x,y;
          x = BookmarkManager.INSTANCE.getBookmarkInfo(id).getLon();
          y = BookmarkManager.INSTANCE.getBookmarkInfo(id).getLat();
          // Check if coordinates match within a tolerance
          if(areCoordinatesEqual(y,x,mMapObject.getLat(),mMapObject.getLon(),1e-12)){
            System.out.println("Bookmark Address Match: " + y + "," + x + " with id: " + id);
            bookmarkID = id;
            break;
          }
        }
        // Check if a matching bookmark was found
        if(bookmarkID == -1){
          Snackbar deleteNotice = Snackbar.make(getView(),"An error occurred, please try again!", Snackbar.LENGTH_SHORT);
          deleteNotice.show();
          return;
        }
        System.out.println("Want to delete bookmark: " + BookmarkManager.INSTANCE.getBookmarkDescription(bookmarkID));
        // Build a DELETE request to remove the pothole bookmark
        Request request = new Request.Builder()
                .url("https://busy-pink-tadpole-toga.cyclic.cloud/api/pothole/deletePothole/" + BookmarkManager.INSTANCE.getBookmarkDescription(bookmarkID))
                .delete()
                .build();
        // Start a new thread to execute the HTTP request
        new Thread(new Runnable() {
          @Override
          public void run() {
            try (Response res = client.newCall(request).execute()){
              if(!res.isSuccessful()) throw new IOException("Unexpected code" + res);
              System.out.println(res.toString());
              Snackbar deleteNotice = Snackbar.make(getView(),"Removed pothole from the map!", Snackbar.LENGTH_SHORT);
              deleteNotice.show();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        }).start();
      }
      // Call native method to delete bookmark from the map object
      Framework.nativeDeleteBookmarkFromMapObject();
    }
    else
      // If the place is not bookmarked, add a new bookmark
      BookmarkManager.INSTANCE.addNewBookmark(mMapObject.getLat(), mMapObject.getLon());
  }

  /**
   * Handles the click event for the bookmark pothole button.
   * Checks if the "Potholes" category is present, and if not, initiates a network task to create the category.
   * If the category is already present, displays a Snackbar message.
   */
  private void onBookmarkPotholeBtnClicked()
  {
    // Get bookmark Category
    List<BookmarkCategory> categories = BookmarkManager.INSTANCE.getCategories();
    // Check if the "Potholes" category is present
    boolean potholeCatPresent = false;
    for(int i = 0; i < categories.size(); i++){
      if(categories.get(i).getName().equals("Potholes")){
        potholeCatPresent = true;
        break;
      }
    }
    // If the "Potholes" category is not present, initiate a network task to create it
    if(!potholeCatPresent){
      new NetworkTask().execute();
    }else{
      // Display a Snackbar message if the category is already present
      Snackbar deleteNotice = Snackbar.make(getView(),"Please delete Pothole List First!", Snackbar.LENGTH_SHORT);
      deleteNotice.show();
    }
  }
  /**
   * An asynchronous task to perform network operations for downloading pothole data and updating bookmarks.
   */
  private class NetworkTask extends AsyncTask<Void, Void, ArrayList<Pair<Pair<Double,Double>,String>>> {
    @Override
    protected ArrayList<Pair<Pair<Double,Double>,String>> doInBackground(Void... voids) {
      // ArrayList to store results (pairs of coordinates and bookmark IDs)
      ArrayList<Pair<Pair<Double,Double>,String>> results = new ArrayList<>();
      OkHttpClient client = new OkHttpClient();
      // Build a request to get all potholes from the server
      Request request = new Request.Builder()
              .url("https://busy-pink-tadpole-toga.cyclic.cloud/api/pothole/getAllPotholes")
              .build();

      try (Response res = client.newCall(request).execute()) {
        if (!res.isSuccessful()) {
          throw new IOException("Unexpected code " + res);
        }
        // Parse the JSON response
        JSONArray arrRes = new JSONArray(res.body().string());

        // Iterate through the potholes and extract coordinates and IDs
        for (int i = 0; i < arrRes.length(); i++) {
          JSONObject obj = arrRes.getJSONObject(i);
          double lat = obj.getDouble("Latitude");
          double lon = obj.getDouble("Longitude");
          Pair<Double,Double> pair = new Pair<>(lat,lon);
          String id = obj.getString("_id");
          results.add(new Pair<>(pair,id));
        }
      } catch (IOException | JSONException e) {
        // Return null if an exception occurs during network operations
        return null;
      }
      // Return the results to the onPostExecute method
      return results;
    }

    @Override
    protected void onPostExecute(ArrayList<Pair<Pair<Double,Double>,String>> results) {
      // Check if the results are not null
      if (results != null) {
        // Create the "Potholes" category if it doesn't exist
        BookmarkManager.INSTANCE.createCategory("Potholes");

        // Display a Snackbar message indicating successful pothole list download
        Snackbar doneNotice = Snackbar.make(getView(),"Pothole List Downloaded!", Snackbar.LENGTH_SHORT);

        // Iterate through the results and update bookmarks
        for (int i = 0; i < results.size(); i++) {
          Pair<Pair<Double,Double>,String> coords = results.get(i);
          System.out.println("Received Bookmark: " + coords.first.first + ","+ coords.first.second + " : " + coords.second);

          // Add a new bookmark with coordinates
          Bookmark bookmark = BookmarkManager.INSTANCE.addNewBookmark(coords.first.first, coords.first.second);

          // Update the pothole bookmark with the received ID
          BookmarkManager.INSTANCE.updatePotholeBookmark(bookmark.getBookmarkId(),coords.second);
        }

        // Show the Snackbar message
        doneNotice.show();
      } else {
        // Log an error message if an error occurred during network operations
        System.out.println("Error has occurred!");
      }
    }
  }

  private void onBackBtnClicked()
  {
    if (mMapObject == null)
      return;
    final ParsedMwmRequest request = ParsedMwmRequest.getCurrentRequest();
    if (request != null && request.isPickPointMode())
    {
      final Intent result = new Intent();
      result.putExtra(Const.EXTRA_POINT_LAT, mMapObject.getLat())
            .putExtra(Const.EXTRA_POINT_LON, mMapObject.getLon())
            .putExtra(Const.EXTRA_POINT_NAME, mMapObject.getTitle())
            .putExtra(Const.EXTRA_POINT_ID, mMapObject.getApiId())
            .putExtra(Const.EXTRA_ZOOM_LEVEL, Framework.nativeGetDrawScale());
      requireActivity().setResult(Activity.RESULT_OK, result);
      ParsedMwmRequest.setCurrentRequest(null);
    }
    requireActivity().finish();
  }

  private void onRouteFromBtnClicked()
  {
    if (mMapObject == null)
      return;
    RoutingController controller = RoutingController.get();
    if (!controller.isPlanning())
    {
      controller.prepare(mMapObject, null);
      close();
    }
    else if (controller.setStartPoint(mMapObject))
      close();
  }

  private void onRouteToBtnClicked()
  {
    if (mMapObject == null)
      return;
    if (RoutingController.get().isPlanning())
    {
      RoutingController.get().setEndPoint(mMapObject);
      close();
    }
    else
      ((MwmActivity) requireActivity()).startLocationToPoint(mMapObject);
  }

  private void onRouteAddBtnClicked()
  {
    if (mMapObject != null)
      RoutingController.get().addStop(mMapObject);
  }

  private void onRouteRemoveBtnClicked()
  {
    if (mMapObject != null)
      RoutingController.get().removeStop(mMapObject);
  }

  private void onAvoidUnpavedBtnClicked()
  {
    onAvoidBtnClicked(RoadType.Dirty);
  }

  private void onAvoidFerryBtnClicked()
  {
    onAvoidBtnClicked(RoadType.Ferry);
  }

  private void onAvoidTollBtnClicked()
  {
    onAvoidBtnClicked(RoadType.Toll);
  }

  private void onAvoidBtnClicked(@NonNull RoadType roadType)
  {
    if (mMapObject != null)
      mPlacePageRouteSettingsListener.onPlacePageRequestToggleRouteSettings(roadType);
  }

  private void removePlacePageFragments()
  {
    final FragmentManager fm = getChildFragmentManager();
    final Fragment placePageButtonsFragment = fm.findFragmentByTag(PLACE_PAGE_BUTTONS_FRAGMENT_TAG);
    final Fragment placePageFragment = fm.findFragmentByTag(PLACE_PAGE_FRAGMENT_TAG);

    if (placePageButtonsFragment != null)
    {
      fm.beginTransaction()
        .setReorderingAllowed(true)
        .remove(placePageButtonsFragment)
        .commit();
    }
    if (placePageFragment != null)
    {
      fm.beginTransaction()
        .setReorderingAllowed(true)
        .remove(placePageFragment)
        .commit();
    }
    mViewModel.setMapObject(null);
  }

  private void createPlacePageFragments()
  {
    final FragmentManager fm = getChildFragmentManager();
    if (fm.findFragmentByTag(PLACE_PAGE_FRAGMENT_TAG) == null)
    {
      fm.beginTransaction()
        .setReorderingAllowed(true)
        .add(R.id.placepage_fragment, PlacePageView.class, null, PLACE_PAGE_FRAGMENT_TAG)
        .commit();
    }
    if (fm.findFragmentByTag(PLACE_PAGE_BUTTONS_FRAGMENT_TAG) == null)
    {
      fm.beginTransaction()
        .setReorderingAllowed(true)
        .add(R.id.pp_buttons_fragment, PlacePageButtons.class, null, PLACE_PAGE_BUTTONS_FRAGMENT_TAG)
        .commit();
    }
  }

  @SuppressLint("SuspiciousIndentation")
  private void updateButtons(MapObject mapObject, boolean showBackButton, boolean showRoutingButton)
  {
    List<PlacePageButtons.ButtonType> buttons = new ArrayList<>();
    if (mapObject.getRoadWarningMarkType() != RoadWarningMarkType.UNKNOWN)
    {
      RoadWarningMarkType markType = mapObject.getRoadWarningMarkType();
      PlacePageButtons.ButtonType roadType = toPlacePageButton(markType);
      buttons.add(roadType);
    }
    else if (RoutingController.get().isRoutePoint(mapObject))
    {
      buttons.add(PlacePageButtons.ButtonType.ROUTE_REMOVE);
    }
    else
    {
      final ParsedMwmRequest request = ParsedMwmRequest.getCurrentRequest();
      if (showBackButton || (request != null && request.isPickPointMode()))
        buttons.add(PlacePageButtons.ButtonType.BACK);

      boolean needToShowRoutingButtons = RoutingController.get().isPlanning() || showRoutingButton;

      if (needToShowRoutingButtons)
        buttons.add(PlacePageButtons.ButtonType.ROUTE_FROM);

      // If we can show the add route button, put it in the place of the bookmark button
      // And move the bookmark button at the end
      if (needToShowRoutingButtons && RoutingController.get().isStopPointAllowed())
        buttons.add(PlacePageButtons.ButtonType.ROUTE_ADD);
      else
        buttons.add(mapObject.isBookmark()
                    ? PlacePageButtons.ButtonType.BOOKMARK_DELETE
                    : PlacePageButtons.ButtonType.BOOKMARK_SAVE);
        buttons.add(mapObject.isBookmark()
              ? PlacePageButtons.ButtonType.POTHOLE
              : PlacePageButtons.ButtonType.POTHOLE);

      if (needToShowRoutingButtons)
      {
        buttons.add(PlacePageButtons.ButtonType.ROUTE_TO);
        if (RoutingController.get().isStopPointAllowed())
          buttons.add(mapObject.isBookmark()
                      ? PlacePageButtons.ButtonType.BOOKMARK_DELETE
                      : PlacePageButtons.ButtonType.BOOKMARK_SAVE);
      }
    }
    mViewModel.setCurrentButtons(buttons);
  }

  @Override
  public void onChanged(@Nullable MapObject mapObject)
  {
    mMapObject = mapObject;
    if (mapObject != null)
    {
      setPlacePageInteractions(true);
      // Only collapse the place page if the data is different from the one already available
      mShouldCollapse = PlacePageUtils.isHiddenState(mPlacePageBehavior.getState()) || !MapObject.same(mPreviousMapObject, mMapObject);
      mPreviousMapObject = mMapObject;
      // Place page will automatically open when the bottom sheet content is loaded so we can compute the peek height
      createPlacePageFragments();
      updateButtons(
          mapObject,
          mMapObject.isApiPoint(),
          !mMapObject.isMyPosition());
    }
    else
      close();
  }

  private void updateStatusBarBackground(int distanceToTop)
  {
    // This callback may be called before insets are updated when resuming the app
    if (mCurrentWindowInsets == null)
      return;
    final int topInset = mCurrentWindowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
    // Only animate the status bar background if the place page can reach it
    if (mCoordinator.getHeight() - mPlacePageContainer.getHeight() < topInset)
    {
      final int animationStartHeight = topInset * 3;
      int newHeight = 0;
      if (distanceToTop < animationStartHeight)
        newHeight = Math.min(topInset * (animationStartHeight - distanceToTop) / 100, topInset);
      if (newHeight > 0)
      {
        mPlacePageStatusBarBackground.setTranslationY(distanceToTop - newHeight);
        if (!UiUtils.isVisible(mPlacePageStatusBarBackground))
          UiUtils.show(mPlacePageStatusBarBackground);
      }
      else if (UiUtils.isVisible(mPlacePageStatusBarBackground))
        UiUtils.hide(mPlacePageStatusBarBackground);
    }
  }

  @Override
  public void onStart()
  {
    super.onStart();
    mPlacePageBehavior.addBottomSheetCallback(mDefaultBottomSheetCallback);
    mViewModel.getMapObject().observe(requireActivity(), this);
    mViewModel.getPlacePageDistanceToTop().observe(requireActivity(), mPlacePageDistanceToTopObserver);
  }

  @Override
  public void onResume()
  {
    super.onResume();
    if (mPlacePageBehavior.getState() != BottomSheetBehavior.STATE_HIDDEN && !Framework.nativeHasPlacePageInfo())
      mViewModel.setMapObject(null);
  }

  @Override
  public void onStop()
  {
    super.onStop();
    mPlacePageBehavior.removeBottomSheetCallback(mDefaultBottomSheetCallback);
    mViewModel.getMapObject().removeObserver(this);
    mViewModel.getPlacePageDistanceToTop().removeObserver(mPlacePageDistanceToTopObserver);
  }

  public interface PlacePageRouteSettingsListener
  {
    void onPlacePageRequestToggleRouteSettings(@NonNull RoadType roadType);
  }

}
