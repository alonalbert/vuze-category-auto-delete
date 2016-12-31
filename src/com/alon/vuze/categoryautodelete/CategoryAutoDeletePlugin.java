package com.alon.vuze.categoryautodelete;

import org.gudy.azureus2.plugins.Plugin;
import org.gudy.azureus2.plugins.PluginException;
import org.gudy.azureus2.plugins.PluginInterface;
import org.gudy.azureus2.plugins.download.Download;
import org.gudy.azureus2.plugins.download.DownloadCompletionListener;
import org.gudy.azureus2.plugins.download.DownloadEventNotifier;
import org.gudy.azureus2.plugins.download.DownloadException;
import org.gudy.azureus2.plugins.download.DownloadManager;
import org.gudy.azureus2.plugins.download.DownloadRemovalVetoException;
import org.gudy.azureus2.plugins.logging.LoggerChannel;
import org.gudy.azureus2.plugins.logging.LoggerChannelListener;
import org.gudy.azureus2.plugins.torrent.TorrentAttribute;
import org.gudy.azureus2.plugins.torrent.TorrentManager;
import org.gudy.azureus2.plugins.ui.components.UITextArea;
import org.gudy.azureus2.plugins.ui.config.IntParameter;
import org.gudy.azureus2.plugins.ui.config.StringParameter;
import org.gudy.azureus2.plugins.ui.model.BasicPluginConfigModel;
import org.gudy.azureus2.plugins.ui.model.BasicPluginViewModel;
import org.gudy.azureus2.plugins.utils.Utilities;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class CategoryAutoDeletePlugin implements Plugin, LoggerChannelListener, DownloadCompletionListener {
  private static final String TA_COMPLETED_TIME = "completedTime";

  private final Timer timer = new Timer(true);
  private UITextArea logArea;
  private LoggerChannel logger;
  private DownloadManager downloadManager;
  private TorrentAttribute categoryAttribute;
  private TorrentAttribute completedTimeAttribute;

  private StringParameter categories;
  private IntParameter duration;

  public void initialize(PluginInterface pluginInterface) throws PluginException {
    downloadManager = pluginInterface.getDownloadManager();
    logger = pluginInterface.getLogger().getTimeStampedChannel("Category Auto Delete");
    BasicPluginViewModel viewModel = pluginInterface.getUIManager().createBasicPluginViewModel("Category Auto Delete");
    logArea = viewModel.getLogArea();
    logger.addListener(this);
    final TorrentManager torrentManager = pluginInterface.getTorrentManager();
    categoryAttribute = torrentManager.getAttribute(TorrentAttribute.TA_CATEGORY);
    completedTimeAttribute = torrentManager.getPluginAttribute(TA_COMPLETED_TIME);
    final DownloadEventNotifier eventNotifier = downloadManager.getGlobalDownloadEventNotifier();
    eventNotifier.addCompletionListener(this);
    createConfigModule(pluginInterface);
  }

  private void createConfigModule(PluginInterface pluginInterface) {
    final BasicPluginConfigModel configModel = pluginInterface.getUIManager()
            .createBasicPluginConfigModel("categoryautodelete");
    configModel.addLabelParameter2("categoryautodelete.title");
    categories = configModel.addStringParameter2("sections", "categoryautodelete.sections", "");
    duration = configModel.addIntParameter2("duration", "categoryautodelete.duration", 30);

    configModel.addActionParameter2(null, "categoryautodelete.check_now_button")
        .addListener(param -> checkDownloads());

    Utilities utilities = pluginInterface.getUtilities();
    timer.schedule(new TimerTask() {
      @Override
      public void run() {
        checkDownloads();
      }
    }, TimeUnit.MINUTES.toMillis(5), TimeUnit.DAYS.toMillis(1));
  }

  @Override
  public void messageLogged(int type, String content) {
    logArea.appendText(content + "\n");
  }

  @Override
  public void messageLogged(String str, Throwable error) {
    logArea.appendText(str + "\n");
    StringWriter writer = new StringWriter();
    error.printStackTrace(new PrintWriter(writer));
    logArea.appendText(writer.toString() + "\n");
  }

  private void checkDownloads() {
    try {
      logger.log("Checking downloads...");
      final Set<Pattern> categoryPatterns = new HashSet<>();
      for (String regex : categories.getValue().split(",")) {
        categoryPatterns.add(Pattern.compile(regex.trim()));
      }
      final long now = System.currentTimeMillis();

      final Download[] downloads = downloadManager.getDownloads();
      for (Download download : downloads) {
        if (!download.isComplete()) {
          continue;
        }
        final long completedTime = download.getLongAttribute(completedTimeAttribute);
        if (completedTime == 0) {
          onCompletion(download);
          continue;
        }
        final String category = download.getAttribute(categoryAttribute);
        if (checkCategory(categoryPatterns, category)) {
          final long time = TimeUnit.MILLISECONDS.toMinutes(now - completedTime);
          final long minutes = time % 60;
          final long hours = time / 60 % 60;
          final long days = time / 3600 % 24;
          final StringBuilder timeString = new StringBuilder();
          if (days > 0) {
            timeString.append(String.format("%dd", days));
          }
          if (hours > 0) {
            timeString.append(String.format(" %dh", hours));
          }
          if (days == 0 && minutes > 0) {
            timeString.append(String.format(" %dm", minutes));
          }
          if (timeString.length() == 0) {
            timeString.append("0m");
          }
          logger.log(String.format("%s age is %s", download.getName(), timeString.toString().trim()));
          if (days > duration.getValue()) {
            logger.log(String.format("Deleting %s after %s", timeString, download.getName()));
            //          removeDownload(download);
          }
        }
      }
      logger.log("Done!!!");
    } catch (Exception e) {
      logger.log("Unexpected error", e);
    }
  }

  private boolean checkCategory(Set<Pattern> patterns, String category) {
    if (category == null) {
      return false;
    }
    for (Pattern pattern : patterns) {
      if (pattern.matcher(category).matches()) {
        return true;
      }
    }
    return false;
  }

  private void removeDownload(Download download) {
    try {
      download.remove(true, true);
    } catch (DownloadException | DownloadRemovalVetoException e) {
      logger.log("Error", e);
    }
  }

  @Override
  public void onCompletion(Download download) {
    download.setLongAttribute(completedTimeAttribute, System.currentTimeMillis());
  }
}
