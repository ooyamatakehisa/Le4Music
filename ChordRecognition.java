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
import org.apache.commons.math3.distribution.NormalDistribution;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;

public final class ChordRecognition extends Application {

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
        /* WAVファイル読み込み */
        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final AudioFormat format = stream.getFormat();
        final double sampleRate = format.getSampleRate();
        final double nyquist = sampleRate * 0.5;
        stream.close();


        /* 窓関数とFFTのサンプル数 */
        final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                .orElse(Le4MusicUtils.frameDuration);
        final int frameSize = (int) Math.round(frameDuration * sampleRate);

        
        // これが各フレーム長さ
        int forFrameSize = frameSize/8;
        int hopsize =forFrameSize/2;

        /* fftSize = 2ˆp >= forFrameSize を満たすfftSize を求める
            * 2ˆp はシフト演算で求める*/
        final int fftSize = 1 << Le4MusicUtils.nextPow2(forFrameSize);
        final int fftSize2 = (fftSize >> 1) + 1; //s^(p-1)+1 ??
        
        
        int N = waveform.length;
        int chordNo[] = new int[(N-forFrameSize-1)/hopsize+1];
        for(int frameIndex=0;frameIndex<N-forFrameSize-1;frameIndex+=hopsize){ //すべてのフレームについて

            // そのフレームにおける配列,
            final double[] frameArray = Arrays.copyOfRange(waveform, frameIndex,frameIndex+forFrameSize);

            /* 信号の長さをfftSize に伸ばし， 長さが足りない部分は0 で埋める．
            * 振幅を信号長で正規化する． */
            final double[] src =
                Arrays.stream(Arrays.copyOf(frameArray, fftSize))
                    .map(w -> w / forFrameSize)
                    .toArray();
            /* 高速フーリエ変換を行う*/
            final Complex[] spectrum = Le4MusicUtils.rfft(src); // 2^p -> 2^(p-1)+1

            double cv[] = new double[12];
            for(int noteNumber=36;noteNumber<96;noteNumber++){
                int arrayIndex = (int) Math.round(Le4MusicUtils.nn2hz(noteNumber)*spectrum.length/sampleRate);
                cv[noteNumber%12] += (spectrum[arrayIndex]).abs();
            }            

            double chords[] = new double[24];
            for(int chordIndex=0;chordIndex<24;chordIndex++){
                int index = (int) chordIndex/2;
                if(chordIndex%2!=0){ chords[chordIndex] = cv[index] + cv[(index+3)%12] + cv[(index+7)%12];}
                else{ chords[chordIndex] = cv[index] + cv[(index+4)%12] + cv[(index+7)%12]; }
            }
            chordNo[frameIndex/hopsize] = Le4MusicUtils.argmax(chords);
        }


        /* データ系列を作成*/
        final ObservableList<XYChart.Data<Number, Number>> data =
            IntStream.range(0, chordNo.length)
                     .mapToObj(i -> new XYChart.Data<Number, Number> (i*hopsize / sampleRate, chordNo[i]))
                     .collect(Collectors.toCollection(FXCollections::observableArrayList));

        
        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> series =
        new XYChart.Series<>("aiueo", data);

        
        /* X 軸を作成*/
        final double duration = (chordNo.length*hopsize - 1) / sampleRate;
        final NumberAxis xAxis = new NumberAxis(
            /* axisLabel = */ "Time (seconds)",
            /* lowerBound = */ 0.0,
            /* upperBound = */ duration,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(duration)
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

            /* lowerBound = */ 0,
            /* upperBound = */ 24,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(ampUpperBound - ampLowerBound)
        );
        yAxis.setAnimated(false);

        /* チャートを作成*/
        final LineChart<Number, Number> chart =
            new LineChart<>(xAxis, yAxis);
        chart.setTitle("Spectrum");
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