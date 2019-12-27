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

import org.apache.commons.math3.complex.Complex;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;

public final class PlotCepstrum extends Application {

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
        options.addOption(null, "amp-lo", true,
                          "Lower bound of amplitude [dB] (Default: " +
                          Le4MusicUtils.spectrumAmplitudeLowerBound + ")");
        options.addOption(null, "amp-up", true,
                          "Upper bound of amplitude [dB] (Default: " +
                          Le4MusicUtils.spectrumAmplitudeUpperBound + ")");
        options.addOption(null, "freq-lo", true,
                          "Lower bound of frequency [Hz] (Default: 0.0)");
        options.addOption(null, "freq-up", true,
                          "Upper bound of frequency [Hz] (Default: Nyquist)");
    }

    @Override public final void start(final Stage primaryStage)
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
        final double nyquist = sampleRate * 0.5;
        stream.close();

        /* fftSize = 2ˆp >= waveform.length を満たすfftSize を求める
        * 2ˆp はシフト演算で求める*/
        final int fftSize = 1 << Le4MusicUtils.nextPow2(waveform.length);
        final int fftSize2 = (fftSize >> 1) + 1; //s^(p-1)+1 ??
        /* 信号の長さをfftSize に伸ばし， 長さが足りない部分は0 で埋める．
        * 振幅を信号長で正規化する． */
        final double[] src =
            Arrays.stream(Arrays.copyOf(waveform, fftSize))
                  .map(w -> w / waveform.length)
                  .toArray();
        /* 高速フーリエ変換を行う*/
        final Complex[] spectrum = Le4MusicUtils.rfft(src); // s^p -> s^(p-1)+1


        // ケプストラム求める！

        /* 1.対数振幅スペクトルを求める*/
        final double[] specLog =
            Arrays.stream(spectrum)
                  .mapToDouble(c -> 20*Math.log10(c.abs()))
                  .toArray();

        /* 1.5 スペクトルの配列の長さを2のn上にする*/
        final double normalizeSpecLog[] = new double[(int)Math.pow(2,Le4MusicUtils.nextPow2(specLog.length))];
        System.out.println(specLog.length +" " + (int)Math.pow(2,Le4MusicUtils.nextPow2(specLog.length)));
        for(int i=0;i<specLog.length;i++){
            normalizeSpecLog[i] = specLog[i];
        }
        // Arrays.stream(normalizeSpecLog).forEach(s -> System.out.println(s));
        
        
        /* 2.対数振幅スペクトルをフーリエ変換する*/
        final Complex[] freaquencySpectrum = Le4MusicUtils.rfft(normalizeSpecLog);  //2^n -> 2^(n-1)+1

        /* 3.フーリエ変換の結果のうち，低い周波数の成分のみを取り出す．*/
        for(int i=14;i<freaquencySpectrum.length;i++){
             freaquencySpectrum[i] = new Complex(0,0);
        }
        
         /* 4.取り出した成分のみを逆フーリエ変換する．*/
        final double[] ans = Le4MusicUtils.irfft(freaquencySpectrum);  //2^(n-1)+1 -> 2^n  
        final int cepfftSize = freaquencySpectrum.length;
        final int cepfftSize2 = ans.length; //  2^n   ??

        

        /* スペクトル配列の各要素に対応する周波数を求める．
        * 以下を満たすように線型に
        * freqs[0] = 0Hz
        * freqs[fftSize2 - 1] = sampleRate / 2 (= Nyquist周波数) */
        
        final double[] freqs =
            IntStream.range(0, fftSize2)
                     .mapToDouble(i -> i * sampleRate / fftSize)
                     .toArray();
        // final double[] cepfreqs =
        //     IntStream.range(0, cepfftSize2)
        //              .mapToDouble(i -> i * sampleRate / cepfftSize)
        //              .toArray();
        

        /* データ系列を作成*/
        final ObservableList<XYChart.Data<Number, Number>> data =
            IntStream.range(0, fftSize2)
                     .mapToObj(i -> new XYChart.Data<Number, Number>(freqs[i], specLog[i]))
                     .collect(Collectors.toCollection(FXCollections::observableArrayList));

        final ObservableList<XYChart.Data<Number, Number>> cepdata =
            IntStream.range(0, fftSize2)
                     .mapToObj(i -> new XYChart.Data<Number, Number>(freqs[i], ans[i]))
                     .collect(Collectors.toCollection(FXCollections::observableArrayList));
        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> series =
        new XYChart.Series<>("spectrum", data);

        final XYChart.Series<Number, Number> cepseries =
        new XYChart.Series<>("cepstrum", cepdata);

        /* X 軸を作成*/
        final double freqLowerBound =
            Optional.ofNullable(cmd.getOptionValue("freq-lo"))
                    .map(Double::parseDouble)
                    .orElse(0.0);
        if (freqLowerBound < 0.0)
            throw new IllegalArgumentException(
                "freq-lo must be non-negative: " + freqLowerBound
            );
        final double freqUpperBound =
            Optional.ofNullable(cmd.getOptionValue("freq-up"))
                    .map(Double::parseDouble)
                    .orElse(nyquist);
        if (freqUpperBound <= freqLowerBound)
            throw new IllegalArgumentException(
                "freq-up must be larger than freq-lo: " +
                "freq-lo = " + freqLowerBound + ", freq-up = " + freqUpperBound
            );
        final NumberAxis xAxis = new NumberAxis(
            /* axisLabel = */ "Frequency (Hz)",
            /* lowerBound = */ freqLowerBound,
            /* upperBound = */ freqUpperBound,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(freqUpperBound - freqLowerBound)
        );
        xAxis.setAnimated(false);

        /* Y 軸を作成*/
        final double ampLowerBound =
            Optional.ofNullable(cmd.getOptionValue("amp-lo"))
                    .map(Double::parseDouble)
                    .orElse(Le4MusicUtils.spectrumAmplitudeLowerBound);
        final double ampUpperBound =
            Optional.ofNullable(cmd.getOptionValue("amp-up"))
                    .map(Double::parseDouble)
                    .orElse(Le4MusicUtils.spectrumAmplitudeUpperBound);
        if (ampUpperBound <= ampLowerBound)
            throw new IllegalArgumentException(
                "amp-up must be larger than amp-lo: " +
                "amp-lo = " + ampLowerBound + ", amp-up = " + ampUpperBound
            );
        final NumberAxis yAxis = new NumberAxis(
            /* axisLabel = */ "Amplitude (dB)",

            /* lowerBound = */ -200,
            /* upperBound = */ ampUpperBound,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(ampUpperBound - ampLowerBound)
        );
        yAxis.setAnimated(false);

        /* チャートを作成*/
        final LineChart<Number, Number> chart =
            new LineChart<>(xAxis, yAxis);
        chart.setTitle("Spectrum");
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);
        chart.getData().add(series);
        chart.getData().add(cepseries);

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