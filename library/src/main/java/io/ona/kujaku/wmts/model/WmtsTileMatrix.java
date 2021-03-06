package io.ona.kujaku.wmts.model;

import org.simpleframework.xml.Element;

/**
 * Describes a Wmts TileMatrix object from the WMTS Capabilities object
 *
 * Created by Emmanuel Otin - eo@novel-t.ch 14/01/19.
 */
public class WmtsTileMatrix {

    @Element(name="Identifier")
    private int identifier;

    @Element(name="TileWidth")
    private int tileWidth;

    public int getIdentifier() {
        return this.identifier;
    }

    public int getTileWidth() {
        return this.tileWidth;
    }
}
