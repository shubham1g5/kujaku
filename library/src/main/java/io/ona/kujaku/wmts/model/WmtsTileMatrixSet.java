package io.ona.kujaku.wmts.model;

import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Describe a Wmts TileMatrixSet object from the WMTS Capabilities object
 *
 * Created by Emmanuel Otin - eo@novel-t.ch 14/01/19.
 */
public class WmtsTileMatrixSet {

    @Element(name="Title")
    private String title;

    @Element(name="Abstract")
    private String _abstract; // abstract is a reserved key word

    @Element(name="Identifier")
    private String identifier;

    @Element(name="SupportedCRS")
    private String supportedCRS;

    @ElementList(inline=true, entry="TileMatrix")
    private List<WmtsTileMatrix> tileMatrixs;

    public String getTitle() {
        return this.title;
    }

    public String getAbstract() {
        return this._abstract;
    }

    public String getIdentifier() {
        return this.identifier;
    }

    public String getSupportedCRS() {
        return this.supportedCRS;
    }

    public List<WmtsTileMatrix> getTileMatrixs() {
        return this.tileMatrixs;
    }

    public int getMaximumZoom() {
        if (tileMatrixs == null || tileMatrixs.size() == 0) {
            return 0;
        }

        this.sortTileMatrixsByIdentifier();
        return tileMatrixs.get(tileMatrixs.size()-1).getIdentifier();
    }

    public int getMinimumZoom() {
        if (tileMatrixs == null || tileMatrixs.size() == 0) {
            return 0;
        }

        this.sortTileMatrixsByIdentifier();
        return tileMatrixs.get(0).getIdentifier();
    }

    private void sortTileMatrixsByIdentifier() {
        Collections.sort(tileMatrixs, new Comparator<WmtsTileMatrix>() {
            @Override
            public int compare(WmtsTileMatrix item1, WmtsTileMatrix item2) {
                return item1.getIdentifier() - item2.getIdentifier();
            }
        });
    }
}