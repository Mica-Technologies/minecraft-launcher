/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */


package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;

/**
 * The game log in the Running Games window: one row per line, virtualized, so the cost of a new
 * batch is the rows on screen rather than the whole log.
 *
 * <p>It replaced a wrapping {@code TextArea}. A text area lays out every paragraph of its text,
 * so with the log at its line limit (ten thousand lines and more) each appended batch re-laid
 * out all of them on the UI thread, several times a second for the life of the game. Here the
 * lines live in a list; only the visible rows have nodes, and they wrap to the view's width.</p>
 *
 * <p>Free text selection is replaced by row selection: click, shift- or control-click rows and
 * copy them with the shortcut or the context menu. The search's current match is highlighted
 * within its row. Build and use on the FX thread.</p>
 *
 * @since 2026.10
 */
final class SessionLogView
{
    /** Style class the stylesheet keys the log's look on. */
    static final String STYLE_CLASS = "sessionLog";

    private final ObservableList< String > lines = FXCollections.observableArrayList();
    private final ListView< String >       list  = new ListView<>( lines );

    /** The search's current match: its line, and where in the line it starts and ends. */
    private int matchLine = -1;
    private int matchStart;
    private int matchEnd;

    /** Creates an empty log view. */
    SessionLogView()
    {
        list.getStyleClass().addAll( "text-mono", STYLE_CLASS );
        list.getSelectionModel().setSelectionMode( SelectionMode.MULTIPLE );
        list.setCellFactory( view -> new LineCell() );
        list.setPlaceholder( new Region() );
        // A list asks for 400px by default; the log takes the space left over (its parent grows
        // it), so a small ask keeps the crash card above it from being squeezed to one line.
        list.setPrefHeight( 120 );
        KeyCombination copy = new KeyCodeCombination( KeyCode.C, KeyCombination.SHORTCUT_DOWN );
        list.addEventHandler( KeyEvent.KEY_PRESSED, e -> {
            if ( copy.match( e ) ) {
                copySelection();
                e.consume();
            }
        } );
        MenuItem copyItem = new MenuItem( LocalizationManager.get( "console.copyBtn.label" ) );
        copyItem.setOnAction( e -> copySelection() );
        list.setContextMenu( new ContextMenu( copyItem ) );
    }

    /** @return the node to place in the scene */
    ListView< String > node()
    {
        return list;
    }

    /** @return how many lines are shown */
    int lineCount()
    {
        return lines.size();
    }

    /** @return the lines shown, live; for reading only */
    List< String > lines()
    {
        return lines;
    }

    /**
     * Replaces everything shown.
     *
     * @param text the new text, lines separated by {@code '\n'} (or {@code "\r\n"})
     */
    void setText( String text )
    {
        clearMatch();
        lines.setAll( splitLines( text ) );
    }

    /**
     * Adds lines at the end, then drops lines from the front if the log has grown past the
     * limit's slack (see {@link LogTrimPolicy#shouldTrimDisplay(int, int)}).
     *
     * @param added    the new lines, without line breaks
     * @param maxLines the line limit; {@code <= 0} is unlimited
     * @param follow   whether to keep the last line in view
     *
     * @return how many lines were dropped from the front
     */
    int append( List< String > added, int maxLines, boolean follow )
    {
        int firstVisible = follow ? -1 : firstVisibleIndex();
        lines.addAll( added );
        int dropped = LogTrimPolicy.displayLinesToDrop( lines.size(), maxLines );
        if ( dropped > 0 ) {
            lines.remove( 0, dropped );
            if ( matchLine >= 0 ) {
                matchLine -= dropped;
                if ( matchLine < 0 ) {
                    clearMatch();
                }
            }
        }
        if ( follow ) {
            scrollToEnd();
        }
        else if ( dropped > 0 && firstVisible >= 0 ) {
            // Lines left the front, so the same index is now a later line: keep the reader on the
            // line they were reading.
            list.scrollTo( Math.max( 0, firstVisible - dropped ) );
        }
        return dropped;
    }

    /** Scrolls so the last line is in view. */
    void scrollToEnd()
    {
        if ( !lines.isEmpty() ) {
            list.scrollTo( lines.size() - 1 );
        }
    }

    /** Scrolls so the first line is at the top. */
    void scrollToStart()
    {
        list.scrollTo( 0 );
    }

    /** @return everything shown, each line followed by a line break */
    String text()
    {
        return joinLines( lines );
    }

    /**
     * Moves the search to the next or previous match of {@code needle}, ignoring case, wrapping
     * at either end; highlights it and scrolls it into view.
     *
     * @param needle  the search text
     * @param forward whether to search forward
     *
     * @return {ordinal (1-based), total}, or {@code null} when nothing matches
     */
    int[] find( String needle, boolean forward )
    {
        if ( needle == null || needle.isEmpty() || lines.isEmpty() ) {
            clearMatch();
            list.refresh();
            return null;
        }
        int fromLine;
        int fromCol;
        if ( matchLine >= 0 && matchLine < lines.size() ) {
            fromLine = matchLine;
            fromCol = forward ? matchEnd : matchStart - 1;
        }
        else {
            fromLine = forward ? 0 : lines.size() - 1;
            fromCol = forward ? 0 : lines.get( fromLine ).length();
        }
        int[] at = findMatch( lines, needle, fromLine, fromCol, forward );
        if ( at == null ) {
            clearMatch();
            list.refresh();
            return null;
        }
        matchLine = at[ 0 ];
        matchStart = at[ 1 ];
        matchEnd = at[ 1 ] + needle.length();
        // The match is shown by its highlight; a selected row's colour would hide it.
        list.getSelectionModel().clearSelection();
        list.refresh();
        // A few lines of context above the match rather than pinning it to the top edge.
        list.scrollTo( Math.max( 0, matchLine - 3 ) );
        return matchPosition( lines, needle, matchLine, matchStart );
    }

    private void clearMatch()
    {
        matchLine = -1;
    }

    private void copySelection()
    {
        List< Integer > rows = new ArrayList<>( list.getSelectionModel().getSelectedIndices() );
        if ( rows.isEmpty() ) {
            return;
        }
        rows.sort( null );
        List< String > picked = new ArrayList<>( rows.size() );
        for ( int row : rows ) {
            if ( row >= 0 && row < lines.size() ) {
                picked.add( lines.get( row ) );
            }
        }
        ClipboardContent content = new ClipboardContent();
        content.putString( joinLines( picked ) );
        Clipboard.getSystemClipboard().setContent( content );
    }

    private int firstVisibleIndex()
    {
        if ( list.lookup( ".virtual-flow" ) instanceof VirtualFlow< ? > flow ) {
            IndexedCell< ? > first = flow.getFirstVisibleCell();
            return first == null ? -1 : first.getIndex();
        }
        return -1;
    }

    // ------------------------------------------------------------------ pure helpers

    /**
     * Splits text into lines. A final line break ends the last line rather than starting an
     * empty one, and a {@code '\r'} before a break is dropped. Pure, for testing.
     *
     * @param text the text, may be {@code null}
     *
     * @return the lines, without their breaks
     */
    static List< String > splitLines( String text )
    {
        List< String > out = new ArrayList<>();
        if ( text == null || text.isEmpty() ) {
            return out;
        }
        int start = 0;
        int n = text.length();
        while ( start < n ) {
            int nl = text.indexOf( '\n', start );
            int end = nl < 0 ? n : nl;
            int stop = end > start && text.charAt( end - 1 ) == '\r' ? end - 1 : end;
            out.add( text.substring( start, stop ) );
            if ( nl < 0 ) {
                break;
            }
            start = nl + 1;
        }
        return out;
    }

    /**
     * Joins lines, each followed by a line break, the way the old text area held them. Pure, for
     * testing.
     *
     * @param lines the lines
     *
     * @return the text
     */
    static String joinLines( List< String > lines )
    {
        int size = 0;
        for ( String line : lines ) {
            size += line.length() + 1;
        }
        StringBuilder out = new StringBuilder( size );
        for ( String line : lines ) {
            out.append( line ).append( '\n' );
        }
        return out.toString();
    }

    /**
     * Finds the next (or previous) match of {@code needle}, ignoring case, starting at a line and
     * column and wrapping around the ends. Pure, for testing.
     *
     * @param lines    the lines searched
     * @param needle   the search text, non-empty
     * @param fromLine the line to start in
     * @param fromCol  forward: the first column a match may start at; backward: the last
     * @param forward  whether to search forward
     *
     * @return {line, column} of the match, or {@code null} when there is none
     */
    static int[] findMatch( List< String > lines, String needle, int fromLine, int fromCol, boolean forward )
    {
        int n = lines.size();
        if ( n == 0 || needle == null || needle.isEmpty() ) {
            return null;
        }
        fromLine = Math.floorMod( fromLine, n );
        // n + 1 steps: every line once, then the starting line again for the part skipped.
        for ( int step = 0; step <= n; step++ ) {
            int line = Math.floorMod( fromLine + ( forward ? step : -step ), n );
            String text = lines.get( line );
            int col;
            if ( forward ) {
                col = indexOfIgnoreCase( text, needle, step == 0 ? fromCol : 0 );
            }
            else {
                col = lastIndexOfIgnoreCase( text, needle, step == 0 ? fromCol : text.length() );
            }
            if ( col >= 0 ) {
                return new int[]{ line, col };
            }
        }
        return null;
    }

    /**
     * Which match a search landed on, and how many there are: {ordinal (1-based), total}.
     * Matches don't overlap within a line and ignore case. Pure, for testing.
     *
     * @param lines  the lines searched
     * @param needle the search text, non-empty
     * @param line   the current match's line
     * @param col    the current match's column
     *
     * @return {ordinal, total}
     */
    static int[] matchPosition( List< String > lines, String needle, int line, int col )
    {
        int total = 0;
        int ordinal = 0;
        for ( int l = 0; l < lines.size(); l++ ) {
            String text = lines.get( l );
            for ( int i = indexOfIgnoreCase( text, needle, 0 ); i >= 0;
                  i = indexOfIgnoreCase( text, needle, i + needle.length() ) ) {
                total++;
                if ( l < line || ( l == line && i <= col ) ) {
                    ordinal = total;
                }
            }
        }
        return new int[]{ Math.max( 1, ordinal ), total };
    }

    private static int indexOfIgnoreCase( String text, String needle, int from )
    {
        int last = text.length() - needle.length();
        for ( int i = Math.max( 0, from ); i <= last; i++ ) {
            if ( text.regionMatches( true, i, needle, 0, needle.length() ) ) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOfIgnoreCase( String text, String needle, int from )
    {
        for ( int i = Math.min( from, text.length() - needle.length() ); i >= 0; i-- ) {
            if ( text.regionMatches( true, i, needle, 0, needle.length() ) ) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ rows

    /**
     * One log line. Sized to the view's width (its preferred width is zero, so the list never
     * scrolls sideways) and as tall as the line takes wrapped at that width.
     */
    private final class LineCell extends ListCell< String >
    {
        private final LineGraphic graphic = new LineGraphic();

        LineCell()
        {
            setPrefWidth( 0 );
            setContentDisplay( ContentDisplay.GRAPHIC_ONLY );
        }

        @Override
        protected void updateItem( String item, boolean empty )
        {
            super.updateItem( item, empty );
            if ( empty || item == null ) {
                setGraphic( null );
                return;
            }
            int index = getIndex();
            if ( index == matchLine && matchEnd <= item.length() ) {
                graphic.show( item, matchStart, matchEnd );
            }
            else {
                graphic.show( item, -1, -1 );
            }
            setGraphic( graphic );
        }

        @Override
        protected double computePrefHeight( double width )
        {
            double w = width >= 0 ? width : getWidth();
            if ( w <= 0 ) {
                w = list.getWidth();
            }
            double inner = Math.max( 0, w - snappedLeftInset() - snappedRightInset() );
            double content = getGraphic() == null ? graphic.emptyHeight() : graphic.prefHeight( inner );
            return snappedTopInset() + content + snappedBottomInset();
        }

        @Override
        protected double computeMinHeight( double width )
        {
            return 0;
        }

        @Override
        protected double computeMaxHeight( double width )
        {
            return Double.MAX_VALUE;
        }

        @Override
        protected void layoutChildren()
        {
            // Laid out here rather than by the label skin, which sizes a graphic to its
            // unwrapped width.
            if ( getGraphic() != null ) {
                double left = snappedLeftInset();
                double top = snappedTopInset();
                double w = Math.max( 0, getWidth() - left - snappedRightInset() );
                graphic.resizeRelocate( left, top, w, graphic.prefHeight( w ) );
            }
        }
    }

    /**
     * A line's text, wrapped to its width, with the search's current match drawn on a highlight.
     */
    private static final class LineGraphic extends Region
    {
        private final Text     before    = new Text();
        private final Text     hit       = new Text();
        private final Text     after     = new Text();
        private final TextFlow flow      = new TextFlow( before );
        private final Path     highlight = new Path();
        private       int      hitStart  = -1;
        private       int      hitEnd    = -1;

        LineGraphic()
        {
            before.getStyleClass().add( "sessionLogText" );
            hit.getStyleClass().addAll( "sessionLogText", "sessionLogMatch" );
            after.getStyleClass().add( "sessionLogText" );
            highlight.getStyleClass().add( "sessionLogHighlight" );
            highlight.setManaged( false );
            getChildren().addAll( highlight, flow );
        }

        /**
         * @param line  the line
         * @param start where the highlighted match starts, or {@code -1} for none
         * @param end   where it ends
         */
        void show( String line, int start, int end )
        {
            hitStart = start;
            hitEnd = end;
            if ( start < 0 ) {
                before.setText( line );
                if ( flow.getChildren().size() != 1 ) {
                    flow.getChildren().setAll( before );
                }
            }
            else {
                before.setText( line.substring( 0, start ) );
                hit.setText( line.substring( start, end ) );
                after.setText( line.substring( end ) );
                if ( flow.getChildren().size() != 3 ) {
                    flow.getChildren().setAll( before, hit, after );
                }
            }
            requestLayout();
        }

        /** @return the height of an empty line, so an empty row isn't collapsed */
        double emptyHeight()
        {
            return before.getLayoutBounds().getHeight();
        }

        @Override
        public Orientation getContentBias()
        {
            return Orientation.HORIZONTAL;
        }

        @Override
        protected double computePrefWidth( double height )
        {
            return 0;
        }

        @Override
        protected double computePrefHeight( double width )
        {
            return flow.prefHeight( width );
        }

        @Override
        protected void layoutChildren()
        {
            double w = getWidth();
            flow.resizeRelocate( 0, 0, w, flow.prefHeight( w ) );
            if ( hitStart >= 0 ) {
                flow.layout();
                highlight.getElements().setAll( flow.rangeShape( hitStart, hitEnd ) );
            }
            else {
                highlight.getElements().clear();
            }
        }
    }
}
