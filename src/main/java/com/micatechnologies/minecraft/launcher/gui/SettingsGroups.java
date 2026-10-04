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

import javafx.beans.Observable;
import javafx.scene.Node;
import javafx.scene.Parent;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the Settings panes' row groups tidy as the controller shows and hides controls. A
 * {@code .settingsRow} tile is shown only while at least one of its children is, so hiding a
 * platform-specific toggle doesn't leave an empty rounded box; and the visible rows of each
 * {@code .settingsGroup} are re-tagged first / middle / last ({@link SettingsNavTile#positionClass})
 * so the group's outer corners stay rounded whichever rows remain.
 *
 * @since 2026.10
 */
final class SettingsGroups
{
    private SettingsGroups()
    {
    }

    /**
     * Wires every settings group under {@code root}. Call once, after the FXML has loaded.
     *
     * @param root the screen's root
     */
    static void install( Parent root )
    {
        List< Node > groups = new ArrayList<>();
        collectGroups( root, groups );
        for ( Node group : groups ) {
            if ( !( group instanceof Parent parent ) ) {
                continue;
            }
            List< Node > rows = new ArrayList<>();
            for ( Node child : parent.getChildrenUnmodifiable() ) {
                if ( child.getStyleClass().contains( "settingsRow" ) && child instanceof Parent ) {
                    rows.add( child );
                }
            }
            Runnable refresh = () -> {
                for ( Node row : rows ) {
                    boolean any = ( (Parent) row ).getChildrenUnmodifiable().stream()
                                                  .anyMatch( n -> n.isVisible() && n.isManaged() );
                    row.setVisible( any );
                    row.setManaged( any );
                }
                List< Node > shown = rows.stream().filter( Node::isManaged ).toList();
                for ( Node row : rows ) {
                    row.getStyleClass().removeAll( SettingsNavTile.POSITIONS );
                }
                for ( int i = 0; i < shown.size(); i++ ) {
                    shown.get( i ).getStyleClass().add( SettingsNavTile.positionClass( i, shown.size() ) );
                }
            };
            for ( Node row : rows ) {
                for ( Node child : ( (Parent) row ).getChildrenUnmodifiable() ) {
                    child.visibleProperty().addListener( ( Observable o ) -> refresh.run() );
                    child.managedProperty().addListener( ( Observable o ) -> refresh.run() );
                }
            }
            refresh.run();
        }
    }

    /**
     * Finds the groups under a node. Not {@code lookupAll}: a ScrollPane's content only becomes its
     * child once the skin exists, after the first CSS pass, and this runs before that.
     */
    private static void collectGroups( Node node, List< Node > out )
    {
        if ( node.getStyleClass().contains( "settingsGroup" ) ) {
            out.add( node );
        }
        if ( node instanceof javafx.scene.control.ScrollPane scroll ) {
            if ( scroll.getContent() != null ) {
                collectGroups( scroll.getContent(), out );
            }
        }
        else if ( node instanceof Parent parent ) {
            for ( Node child : parent.getChildrenUnmodifiable() ) {
                collectGroups( child, out );
            }
        }
    }
}
