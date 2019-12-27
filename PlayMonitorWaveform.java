import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.Player;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;

public final class PlayMonitorWaveform extends Application {

  private static final Options options = new Options();
  private static final String helpMessage =
    MethodHandles.lookup().lookupClass().getName() + " [OPTIONS] <WAVFILE>";

  static {
    /* コマンドラインオプション定義 */
    options.addOption("h", "help", false, "Display this help and exit");
    options.addOption("v", "verbose", false, "Verbose output");
    options.addOption("m", "mixer", true,
                      "Index of the Mixer object that " +
                      "supplies a SourceDataLine object. " +
                      "To check the proper index, use CheckAudioSystem");
    options.addOption("l", "loop", false, "Loop playback");
    options.addOption("f", "frame", true,
                      "Frame duration [seconds] " +
                      "(Default: " + Le4MusicUtils.frameDuration + ")");
    options.addOption("i", "interval", true,
                      "Frame notification interval [seconds] " +
                      "(Default: " + Le4MusicUtils.frameInterval + ")");
    options.addOption("b", "buffer", true,
                      "Duration of line buffer [seconds]");
    options.addOption("a", "amp-bounds", true,
                      "Upper(+) and lower(-) bounds in the amplitude direction " +
                      "(Default: " + Le4MusicUtils.waveformAmplitudeBounds + ")");
  }

  @Override /* Application */
  public final void start(final Stage primaryStage)
  throws IOException,
         UnsupportedAudioFileException,
         LineUnavailableException,
         ParseException {
    /* コマンドライン引数処理 */
    final String[] args = getParameters().getRaw().toArray(new String[0]);
    final CommandLine cmd = new DefaultParser().parse(options, args);
    if (cmd.hasOption("help")) {
      new HelpFormatter().printHelp(helpMessage, options);
      Platform.exit();
      return;
    }
    verbose = cmd.hasOption("verbose");
    final String[] pargs = cmd.getArgs();
    if (pargs.length < 1) {
      System.out.println("WAVFILE is not given.");
      new HelpFormatter().printHelp(helpMessage, options);
      Platform.exit();
      return;
    }
    final File wavFile = new File(pargs[0]);

    final double frameDuration =
      Optional.ofNullable(cmd.getOptionValue("frame"))
        .map(Double::parseDouble)
        .orElse(Le4MusicUtils.frameDuration);

    /* Player を作成 */
    final Player.Builder builder = Player.builder(wavFile);
    Optional.ofNullable(cmd.getOptionValue("mixer"))
      .map(Integer::parseInt)
      .map(index -> AudioSystem.getMixerInfo()[index])
      .ifPresent(builder::mixer);
    if (cmd.hasOption("loop"))
      builder.loop();
    Optional.ofNullable(cmd.getOptionValue("buffer"))
      .map(Double::parseDouble)
      .ifPresent(builder::bufferDuration);
    builder.frameDuration(frameDuration);
    Optional.ofNullable(cmd.getOptionValue("interval"))
      .map(Double::parseDouble)
      .ifPresent(builder::interval);
    builder.daemon();
    final Player player = builder.build();

    /* データ系列を作成 */
    final ObservableList<XYChart.Data<Number, Number>> data =
      IntStream.range(0, player.getFrameSize())
        .mapToObj(i -> new XYChart.Data<Number, Number>(i / player.getSampleRate(), 0.0))
        .collect(Collectors.toCollection(FXCollections::observableArrayList));

    /* データ系列に名前をつける */
    final XYChart.Series<Number, Number> series =
      new XYChart.Series<>("Waveform", data);

    /* 軸を作成 */
    final NumberAxis xAxis = new NumberAxis(
      /* axisLabel  = */ "Time (seconds)",
      /* lowerBound = */ -frameDuration,
      /* upperBound = */ 0.0,
      /* tickUnit   = */ Le4MusicUtils.autoTickUnit(frameDuration)
    );
    final double ampBounds =
      Optional.ofNullable(cmd.getOptionValue("amp-bounds"))
        .map(Double::parseDouble)
        .orElse(Le4MusicUtils.waveformAmplitudeBounds);
    final NumberAxis yAxis = new NumberAxis(
      /* axisLabel  = */ "Amplitude",
      /* lowerBound = */ -ampBounds,
      /* upperBound = */ +ampBounds,
      /* tickUnit   = */ Le4MusicUtils.autoTickUnit(ampBounds * 2.0)
    );

    /* チャートを作成 */
    final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
    chart.setTitle("Waveform");
    chart.setCreateSymbols(false);
    chart.setLegendVisible(false);
    chart.setAnimated(false);
    chart.getData().add(series);

    /* 描画ウインドウ作成 */
    final Scene scene  = new Scene(chart, 800, 600);
    scene.getStylesheets().add("src/le4music.css");
    primaryStage.setScene(scene);
    primaryStage.setTitle(getClass().getName());
    primaryStage.show();

    player.addAudioFrameListener((frame, position) -> Platform.runLater(() -> {
      /* 最新フレームの波形を描画 */
      IntStream.range(0, player.getFrameSize()).forEach(i -> {
        data.get(i).setXValue((i + position) / player.getSampleRate());
        data.get(i).setYValue(frame[i]);
      });
      xAxis.setLowerBound(position / player.getSampleRate());
      xAxis.setUpperBound((position + player.getFrameSize()) / player.getSampleRate());
    }));

    Platform.runLater(player::start);
  }

}
