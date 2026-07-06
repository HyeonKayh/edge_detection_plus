//
//  CardGuide.swift
//  WeScan
//

import UIKit

/// Global config for the ID-card guide overlay on the scanner screen.
/// When enabled, auto scan only fires while the detected rectangle fits inside the guide.
public enum CardGuide {

    public static var isEnabled = false

    /// Whether the currently detected rectangle fits inside the guide (updated every frame)
    static var isSatisfied = false

    /// ID-1 card size ratio (85.6mm x 54mm)
    static let aspectRatio: CGFloat = 85.6 / 54.0
}
