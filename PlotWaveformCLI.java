import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.imageio.ImageIO;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.image.WritableImage;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;

public final class PlotWaveformCLI extends Application {

    private static final Options options = new Options();
    private static final String helpMessage =
        MethodHandles.lookup().lookupClass().getName()+" [OPTIONS] <WAVFILE>";

    static {
        /* コマンドラインオプション定義*/
        options.addOption("h", "help", false, "Display this help and exit");
        options.addOption("o", "outfile", true,
                          "Output image file (Default: " +
                          MethodHandles.lookup().lookupClass().getSimpleName() +
                          "." + Le4MusicUtils.outputImageExt + ")");
        options.addOption("a", "amp-bounds", true,
                          "Upper(+) and lower(-) bounds in the amplitude direction " +
                          "(Default: " + Le4MusicUtils.waveformAmplitudeBounds + ")");
    }

   @Override
    public final void start(final Stage primaryStage)
        throws IOException,
                UnsupportedAudioFileException,
                ParseException {
            /* コマンドライン引数処理*/
            final String[] args = getParameters().getRaw().toArray(new String[0]);
            final CommandLine cmd = new DefaultParser().parse(options, args);
            if (cmd.hasOption("help")) {
                new HelpFormatter().printHelp(helpMessage, options);
                Platform.exit();
                return;
        }
        final String[] pargs = cmd.getArgs();
        if (pargs.length < 1) {
            System.out.println("WAVFILE is not given.");
            new HelpFormatter().printHelp(helpMessage, options);
            Platform.exit();
            return;
        }
        final File wavFile = new File(pargs[0]);

        /* W A V ファイル読み込み*/
        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final AudioFormat format = stream.getFormat();
        final double sampleRate = format.getSampleRate();
        stream.close();
        


        /* データ系列を作成*/
        final ObservableList<XYChart.Data<Number, Number>> data =
            IntStream.range(0, waveform.length)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i / sampleRate, waveform[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));

        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> series =
            new XYChart.Series<>("Waveform", data);

        /* X 軸を作成*/
        final double duration = (waveform.length - 1) / sampleRate;
        final NumberAxis xAxis = new NumberAxis(
            /* axisLabel = */ "Time (seconds)",
            /* lowerBound = */ 0.0,
            /* upperBound = */ duration,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(duration)
        );
        xAxis.setAnimated(false);

        /* Y 軸を作成*/
        final double ampBounds =
            Optional.ofNullable(cmd.getOptionValue("amp-bounds"))
                    .map(Double::parseDouble)
                    .orElse(Le4MusicUtils.waveformAmplitudeBounds);
        final NumberAxis yAxis = new NumberAxis(
            /* axisLabel = */ "Amplitude",
            /* lowerBound = */ -ampBounds,
            /* upperBound = */ +ampBounds,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(ampBounds * 2.0)
        );
        yAxis.setAnimated(false);

        /* チャートを作成*/
        final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Waveform");
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.getData().add(series);

        /* グラフ描画*/
        final Scene scene = new Scene(chart, 800, 600);
        scene.getStylesheets().add("src/le4music.css");

        /* ウインドウ表示*/
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();
        


        /* チャートを画像ファイルへ出力*/
        Platform.runLater(() -> {
            final String[] name_ext = Le4MusicUtils.getFilenameWithImageExt(
            Optional.ofNullable(cmd.getOptionValue("outfile")),
            getClass().getSimpleName()
        );
        final WritableImage image = scene.snapshot(null);
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(image, null),
            name_ext[1], new File(name_ext[0] + "." + name_ext[1]));
        } catch (IOException e) { e.printStackTrace(); }
        });
    }

}